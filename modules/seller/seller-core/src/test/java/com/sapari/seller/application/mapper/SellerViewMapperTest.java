package com.sapari.seller.application.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.sapari.seller.domain.model.SellerProfile;
import com.sapari.seller.model.SellerApprovalStatus;
import com.sapari.seller.model.SellerBusinessType;
import com.sapari.seller.view.SellerMeView;
import com.sapari.seller.view.SellerNicknameUpdateResult;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.view.UserView;

class SellerViewMapperTest {

    private final SellerViewMapper mapper = Mappers.getMapper(SellerViewMapper.class);

    @Test
    @DisplayName("UserView와 SellerProfile을 판매자 내정보 View로 조립한다")
    void toMeView() {
        // given
        UserView seller = sellerView();
        SellerProfile sellerProfile = sellerProfile(seller.userId());

        // when
        SellerMeView view = mapper.toMeView(seller, sellerProfile);

        // then
        assertThat(view.userId()).isEqualTo(seller.userId());
        assertThat(view.nickname()).isEqualTo(seller.nickname());
        assertThat(view.name()).isEqualTo(seller.name());
        assertThat(view.birthDate()).isEqualTo(seller.birthDate());
        assertThat(view.phoneNumber()).isEqualTo(seller.phoneNumber());
        assertThat(view.profileImageKey()).isEqualTo(seller.profileImageKey());
        assertThat(view.email()).isEqualTo(seller.email());
        assertThat(view.role()).isEqualTo(seller.role().name());
        assertThat(view.status()).isEqualTo(seller.status().name());
        assertThat(view.grade()).isEqualTo(seller.grade().name());
        assertThat(view.pointBalance()).isEqualTo(seller.pointBalance());
        assertThat(view.marketingAgreed()).isEqualTo(seller.marketingAgreed());
        assertThat(view.storeName()).isEqualTo(sellerProfile.storeName());
        assertThat(view.businessNumber()).isEqualTo(sellerProfile.businessNumber());
        assertThat(view.businessType()).isEqualTo(sellerProfile.businessType());
        assertThat(view.approvalStatus()).isEqualTo(sellerProfile.status());
        assertThat(view.rejectionReason()).isEqualTo(sellerProfile.rejectionReason());
        assertThat(view.approvedAt()).isEqualTo(sellerProfile.approvedAt());
    }

    @Test
    @DisplayName("UserView, SellerProfile, Access Token을 닉네임 변경 결과로 조립한다")
    void toNicknameUpdateResult() {
        // given
        UserView seller = sellerView();
        SellerProfile sellerProfile = sellerProfile(seller.userId());
        String accessToken = "access-token";

        // when
        SellerNicknameUpdateResult result = mapper.toNicknameUpdateResult(seller, sellerProfile, accessToken);

        // then
        assertThat(result.accessToken()).isEqualTo(accessToken);
        assertThat(result.seller().userId()).isEqualTo(seller.userId());
        assertThat(result.seller().businessType()).isEqualTo(sellerProfile.businessType());
        assertThat(result.seller().approvalStatus()).isEqualTo(sellerProfile.status());
    }

    private UserView sellerView() {
        return new UserView(
                UUID.randomUUID(),
                UserRole.SELLER,
                UserStatus.ACTIVE,
                "seller",
                Instant.parse("2026-01-01T00:00:00Z"),
                "홍길동",
                LocalDate.of(1998, 3, 14),
                UserGender.MALE,
                "01012345678",
                "profile-key",
                "seller@example.com",
                UserGrade.BRONZE,
                100,
                true,
                null,
                null,
                null
        );
    }

    private SellerProfile sellerProfile(UUID userId) {
        return new SellerProfile(
                UUID.randomUUID(),
                userId,
                SellerApprovalStatus.APPROVED,
                "사파리상점",
                "1234567890",
                SellerBusinessType.INDIVIDUAL,
                null,
                Instant.parse("2026-01-02T00:00:00Z")
        );
    }
}
