package com.sapari.seller.command;

public record SellerLoginCommand(
        String email,
        String password
) {
}
