package com.sapari.batchapp.user.withdrawal;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import java.util.Optional;
import com.sapari.user.domain.model.User;
import com.sapari.user.model.UserStatus;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.Chunk;

import com.sapari.seller.domain.repository.LocalCredentialRepository;
import com.sapari.seller.domain.repository.SellerProfileRepository;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.port.UserProfileImageCleanupUseCase;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserWithdrawalHardDeleteWriter 테스트")
class UserWithdrawalHardDeleteWriterTest {

    @Mock
    private LocalCredentialRepository localCredentialRepository;

    @Mock
    private SellerProfileRepository sellerProfileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileImageCleanupUseCase imageCleanup;

    @Test
    @DisplayName("탈퇴회원 하위 데이터를 삭제한 뒤 users row를 마지막에 삭제한다")
    void writeDeletesUserOwnedDataBeforeUserRow() throws Exception {
        // given
        UUID userId = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(
                User.builder().userId(userId).status(UserStatus.WITHDRAWING)
                        .profileImageKey("latest-profile.jpg").build()));
        UserWithdrawalHardDeleteWriter writer = new UserWithdrawalHardDeleteWriter(
                localCredentialRepository,
                sellerProfileRepository,
                userRepository,
                imageCleanup
        );

        // when
        writer.write(new Chunk<>(List.of(userId)));

        // then
        InOrder inOrder = inOrder(localCredentialRepository, sellerProfileRepository, userRepository, imageCleanup);
        inOrder.verify(userRepository).findByIdForUpdate(userId);
        inOrder.verify(localCredentialRepository).deleteByUserId(userId);
        inOrder.verify(sellerProfileRepository).deleteByUserId(userId);
        inOrder.verify(userRepository).deleteById(userId);
        inOrder.verify(imageCleanup).scheduleAfterCommit("latest-profile.jpg");
    }

    /** reader 이후 활성 상태로 바뀐 사용자는 하위 데이터까지 보존한다. */
    @Test
    void skipsUserNoLongerWithdrawing() throws Exception {
        UUID id = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(id)).thenReturn(Optional.of(
                User.builder().userId(id).status(UserStatus.ACTIVE).build()));
        new UserWithdrawalHardDeleteWriter(localCredentialRepository, sellerProfileRepository, userRepository, imageCleanup)
                .write(new Chunk<>(List.of(id)));
        verifyNoInteractions(localCredentialRepository, sellerProfileRepository, imageCleanup);
        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).deleteById(id);
    }

    /** 이미 삭제된 회원은 사진 정리도 예약하지 않는다. */
    @Test
    void skipsMissingUser() {
        UUID id = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(id)).thenReturn(Optional.empty());
        new UserWithdrawalHardDeleteWriter(localCredentialRepository, sellerProfileRepository, userRepository, imageCleanup)
                .write(new Chunk<>(List.of(id)));
        verifyNoInteractions(localCredentialRepository, sellerProfileRepository, imageCleanup);
    }
}
