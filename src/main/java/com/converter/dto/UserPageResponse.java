package com.converter.dto;

import com.converter.entity.User;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserPageResponse {

    private List<UserItem> users;
    private long total;
    private int limit;
    private int offset;
    private String status;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UserItem {
        private Long id;
        private String firstName;
        private String lastName;
        private String email;
        private String role;
        private Boolean emailVerified;
        private Instant createdAt;
        private String picture;
        private Boolean isActive;
    }

    public static UserItem fromUser(User u) {
        return UserItem.builder()
                .id(u.getId())
                .firstName(u.getFirstName())
                .lastName(u.getLastName())
                .email(u.getEmail())
                .role(u.getRole().name().toLowerCase())
                .emailVerified(u.isEmailVerified())
                .createdAt(u.getCreatedAt())
                .picture(u.getPicture())
                .isActive(u.isActive())
                .build();
    }
}
