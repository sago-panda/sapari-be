package com.sapari.seller.application.port;

import java.time.LocalDate;

public interface SellerBusinessRegistrationVerifier {

    /**
     * 사업자등록정보가 국세청 정보와 일치하고 가입 가능한 상태인지 확인한다.
     */
    SellerBusinessRegistrationVerification verify(
            String businessNumber,
            String representativeName,
            LocalDate businessStartDate
    );
}
