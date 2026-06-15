package com.sapari.batchapp.withdrawal;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import com.sapari.seller.domain.repository.LocalCredentialRepository;
import com.sapari.seller.domain.repository.SellerProfileRepository;
import com.sapari.user.domain.repository.UserRepository;

@Component
@RequiredArgsConstructor
public class WithdrawnUserHardDeleteWriter implements ItemWriter<UUID> {

    private final LocalCredentialRepository localCredentialRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final UserRepository userRepository;

    @Override
    public void write(Chunk<? extends UUID> chunk) {
        for (UUID userId : chunk) {
            localCredentialRepository.deleteByUserId(userId);
            sellerProfileRepository.deleteByUserId(userId);
            userRepository.deleteById(userId);
        }
    }
}
