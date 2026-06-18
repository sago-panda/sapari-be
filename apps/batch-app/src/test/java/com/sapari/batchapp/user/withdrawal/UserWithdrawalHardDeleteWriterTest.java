package com.sapari.batchapp.user.withdrawal;

import static org.mockito.Mockito.inOrder;

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

@ExtendWith(MockitoExtension.class)
@DisplayName("UserWithdrawalHardDeleteWriter 테스트")
class UserWithdrawalHardDeleteWriterTest {

    @Mock
    private LocalCredentialRepository localCredentialRepository;

    @Mock
    private SellerProfileRepository sellerProfileRepository;

    @Mock
    private UserRepository userRepository;

    @Test
    @DisplayName("탈퇴회원 하위 데이터를 삭제한 뒤 users row를 마지막에 삭제한다")
    void writeDeletesUserOwnedDataBeforeUserRow() throws Exception {
        // given
        UUID userId = UUID.randomUUID();
        UserWithdrawalHardDeleteWriter writer = new UserWithdrawalHardDeleteWriter(
                localCredentialRepository,
                sellerProfileRepository,
                userRepository
        );

        // when
        writer.write(new Chunk<>(List.of(userId)));

        // then
        InOrder inOrder = inOrder(localCredentialRepository, sellerProfileRepository, userRepository);
        inOrder.verify(localCredentialRepository).deleteByUserId(userId);
        inOrder.verify(sellerProfileRepository).deleteByUserId(userId);
        inOrder.verify(userRepository).deleteById(userId);
    }
}
