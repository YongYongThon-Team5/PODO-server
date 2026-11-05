package com.podo.server.auth.domain;

import com.podo.server.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Entity
@Table(name = "accounts", schema = "app")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseTimeEntity {

    @Id
    private UUID id;

    @Column(name = "kakao_subject", nullable = false, unique = true, columnDefinition = "text")
    private String kakaoSubject;

    @Column(nullable = false, columnDefinition = "text")
    private String status;

    public Account(UUID id, String kakaoSubject, String status) {
        this.id = id;
        this.kakaoSubject = kakaoSubject;
        this.status = status;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
