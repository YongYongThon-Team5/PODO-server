package com.podo.server.profile.image;

import com.podo.server.auth.service.AuthSessionService;
import com.podo.server.profile.image.service.ProfileImageService;
import com.podo.server.profile.options.ProfileOptionsProperties;
import com.podo.server.profile.options.ProfileOptionsProperties.ColorOption;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "podo.auth.api-origin=https://podo.test",
        "podo.auth.success-redirect-uri=https://podo.test/auth/success",
        "podo.auth.failure-redirect-uri=https://podo.test/auth/error",
        "podo.auth.csrf-signing-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
@Testcontainers
class ProfileImageIntegrationTests {
    private static final String PATH = "/api/profile-images";
    private static final int MAX_BYTES = 5_242_880;
    private static final int PIXEL = 0xff336699;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11");

    @TempDir
    static Path imageDirectory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("podo.profile.image-directory", () -> imageDirectory.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthSessionService sessions;
    @Autowired ProfileImageService service;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean ProfileOptionsProperties options;
    @LocalServerPort int port;

    @Test
    void uploadedImageCanOnlyBeReadAndLinkedByItsOwner() throws Exception {
        Actor owner = actor();
        Actor other = actor();
        var upload = sendMultipart(owner, png());
        assertThat(upload.statusCode()).withFailMessage(upload.body()).isEqualTo(201);
        assertThat(upload.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(upload.headers().firstValue("X-Request-Id").orElseThrow()).isNotBlank();
        var created = mapper.readTree(upload.body());
        assertThat(created.size()).isEqualTo(3);
        UUID id = UUID.fromString(created.get("imageId").asText());
        assertThat(created.get("contentType").asText()).isEqualTo("image/png");
        assertThat(jdbc.queryForObject("SELECT account_id FROM app.profile_images WHERE id = ?", UUID.class, id))
                .isEqualTo(owner.id());
        assertThat(orphanedAt(id)).isNotNull();
        assertThat(imageDirectory.resolve(id.toString())).isRegularFile();

        String imagePath = PATH + "/" + id;
        HttpRequest readRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + imagePath))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                .header(HttpHeaders.ACCEPT, "image/png")
                .build();
        HttpResponse<byte[]> read;
        try (var client = HttpClient.newHttpClient()) {
            read = client.send(readRequest, HttpResponse.BodyHandlers.ofByteArray());
        }
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.headers().firstValue(HttpHeaders.CONTENT_TYPE)).contains("image/png");
        assertThat(read.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("private, no-store");
        assertThat(read.headers().firstValue("X-Request-Id").orElseThrow()).isNotBlank();
        int sizeBytes = created.get("sizeBytes").asInt();
        assertThat(read.headers().firstValue(HttpHeaders.CONTENT_LENGTH)).contains(Integer.toString(sizeBytes));
        assertThat(read.body()).hasSize(sizeBytes);
        assertThat(ImageIO.read(new ByteArrayInputStream(read.body())).getRGB(0, 0))
                .isEqualTo(PIXEL);
        assertError(perform(get(imagePath).accept("image/png"), other), 404, "RESOURCE_NOT_FOUND");
        assertError(perform(get(PATH + "/" + UUID.randomUUID()), owner), 404, "RESOURCE_NOT_FOUND");
        assertError(mvc.perform(get(imagePath)).andReturn().getResponse(), 401, "AUTHENTICATION_REQUIRED");
        assertError(mvc.perform(multipart(PATH).file(image("file"))).andReturn().getResponse(),
                401, "AUTHENTICATION_REQUIRED");

        when(options.colors()).thenReturn(List.of(new ColorOption("TEST_RED", "시험 빨강", "#ff0000", true)));
        String profile = """
                {"nickname":"시험 아이","colorCode":"TEST_RED","profileImageId":"%s",
                 "interestCategoryIds":[],"bookGenreIds":[]}
                """.formatted(id);
        assertError(perform(post("/api/child-profiles").contentType("application/json").content(profile), other),
                404, "RESOURCE_NOT_FOUND");
        var linked = perform(post("/api/child-profiles").contentType("application/json").content(profile), owner);
        assertThat(linked.getStatus()).withFailMessage(linked.getContentAsString()).isEqualTo(201);
        assertThat(mapper.readTree(linked.getContentAsByteArray()).get("profileImageId").asText()).isEqualTo(id.toString());
        assertThat(orphanedAt(id)).isNull();
    }

    @Test
    void uploadRejectsMissingRepeatedOrExtraFieldsAndQueryParameters() throws Exception {
        Actor owner = actor();
        MockMultipartFile file = image("file");
        for (var request : List.of(multipart(PATH), multipart(PATH).file(file).file(file),
                multipart(PATH).file(file).file(image("other")),
                multipart(PATH).file(file).param("accountId", owner.id().toString()),
                multipart(PATH).file(file).queryParam("storageKey", "user-value"))) {
            assertError(perform(request, owner), 400, "INVALID_REQUEST");
        }
        assertNoUploads(owner);
    }

    @Test
    void uploadRejectsInvalidImagesAndOversizedFileBytes() throws Exception {
        Actor owner = actor();
        assertError(perform(multipart(PATH).file(new MockMultipartFile("file", "broken.png", "image/png",
                new byte[] {1, 2, 3})), owner), 415, "UNSUPPORTED_IMAGE_TYPE");
        assertError(perform(multipart(PATH).file(new MockMultipartFile("file", "mismatch.jpg", "image/jpeg",
                png())), owner), 415, "UNSUPPORTED_IMAGE_TYPE");
        assertError(perform(multipart(PATH).file(new MockMultipartFile("file", "large.png", "image/png",
                new byte[MAX_BYTES + 1])), owner), 413, "UPLOAD_TOO_LARGE");
        assertNoUploads(owner);
    }

    @Test
    void servletMultipartSizeLimitReturnsTheCommonUploadError() throws Exception {
        Actor owner = actor();
        var response = sendMultipart(owner, new byte[MAX_BYTES + 1]);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(413);
        var error = mapper.readTree(response.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("UPLOAD_TOO_LARGE");
        assertThat(response.headers().firstValue("X-Request-Id")).contains(error.get("requestId").asText());
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertNoUploads(owner);
    }

    @Test
    void transactionRollbackRemovesBytesWrittenForAnUncommittedUpload() throws Exception {
        Actor owner = actor();
        byte[] bytes = png();
        UUID id = new TransactionTemplate(transactionManager).execute(status -> {
            UUID imageId = service.upload(owner.id(), bytes, "image/png").imageId();
            assertThat(imageDirectory.resolve(imageId.toString())).isRegularFile();
            status.setRollbackOnly();
            return imageId;
        });
        assertThat(imageDirectory.resolve(id.toString())).doesNotExist();
        assertNoUploads(owner);
    }

    private HttpResponse<String> sendMultipart(Actor owner, byte[] bytes) throws Exception {
        String boundary = "profile-image-boundary";
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"original.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token())
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofByteArray(bytes),
                        HttpRequest.BodyPublishers.ofByteArray(suffix)))
                .build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void generatedOpenApiDescribesTheMultipartFileAndPrivateBinaryResponse() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(200);
        var document = mapper.readTree(response.getContentAsByteArray());
        var upload = document.at("/paths/~1api~1profile-images/post");
        var read = document.at("/paths/~1api~1profile-images~1{imageId}/get");
        var multipart = upload.at("/requestBody/content/multipart~1form-data");
        var schema = multipart.path("schema");
        if (schema.has("$ref")) schema = document.at(schema.path("$ref").asText().substring(1));
        assertThat(schema.at("/properties/file/format").asText()).isEqualTo("binary");
        assertThat(schema.path("properties").size()).isEqualTo(1);
        assertThat(schema.at("/required/0").asText()).isEqualTo("file");
        assertThat(schema.path("additionalProperties").isBoolean()).isTrue();
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(upload.at("/security/0/BearerAuth").isArray()).isTrue();
        assertThat(read.at("/security/0/BearerAuth").isArray()).isTrue();
        for (String imageType : List.of("jpeg", "png", "webp")) {
            assertThat(read.at("/responses/200/content/image~1" + imageType + "/schema/format").asText())
                    .isEqualTo("binary");
        }
    }

    private void assertNoUploads(Actor owner) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.profile_images WHERE account_id = ?",
                Long.class, owner.id())).isZero();
    }

    private Timestamp orphanedAt(UUID id) {
        return jdbc.queryForObject("SELECT orphaned_at FROM app.profile_images WHERE id = ?", Timestamp.class, id);
    }

    private Actor actor() {
        var login = sessions.issueForKakaoSubject("TEST_" + UUID.randomUUID());
        var tokens = sessions.rotateRefresh(login.refreshToken());
        return new Actor(tokens.accountId(), tokens.accessToken());
    }

    private MockHttpServletResponse perform(AbstractMockHttpServletRequestBuilder<?> request, Actor actor) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.token()))
                .andReturn().getResponse();
    }

    private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(status);
        var error = mapper.readTree(response.getContentAsByteArray()).get("error");
        assertThat(error.get("code").asText()).isEqualTo(code);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(error.get("requestId").asText());
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    private MockMultipartFile image(String part) throws Exception {
        return new MockMultipartFile(part, "original.png", "image/png", png());
    }

    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, PIXEL);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private record Actor(UUID id, String token) { }
}
