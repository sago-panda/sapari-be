package com.sapari.seller.infrastructure.external;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sapari.seller.application.port.SellerBusinessRegistrationVerification;
import com.sapari.seller.application.port.SellerBusinessRegistrationVerifier;

@Slf4j
@Component
public class NtsBusinessRegistrationVerifier implements SellerBusinessRegistrationVerifier {

    private static final String VALID_REGISTRATION_CODE = "01";
    private static final String ACTIVE_BUSINESS_STATUS_CODE = "01";
    private static final String OK_STATUS_CODE = "OK";
    private static final DateTimeFormatter BUSINESS_START_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient restClient;
    private final NtsBusinessRegistrationProperties properties;

    public NtsBusinessRegistrationVerifier(
            @Qualifier("ntsBusinessRegistrationRestClient") RestClient restClient,
            NtsBusinessRegistrationProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * 국세청 진위확인 결과를 가입 가능 여부로 변환한다.
     */
    @Override
    public SellerBusinessRegistrationVerification verify(
            String businessNumber,
            String representativeName,
            LocalDate businessStartDate
    ) {
        if (properties.serviceKey() == null || properties.serviceKey().isBlank()) {
            log.warn("NTS business registration service key is not configured.");
            return SellerBusinessRegistrationVerification.unavailable();
        }

        try {
            NtsBusinessRegistrationResponse response = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/validate")
                            .queryParam("serviceKey", properties.serviceKey()) //  API 스펙상 query parameter
                            .build()
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new NtsBusinessRegistrationRequest(List.of(
                            NtsBusinessRegistrationPayload.of(
                                    businessNumber,
                                    representativeName,
                                    businessStartDate
                            )
                    )))
                    .retrieve()
                    .body(NtsBusinessRegistrationResponse.class);

            return toVerification(response, businessNumber);
        } catch (RestClientException e) {
            log.warn("NTS business registration verification failed.", e);
            return SellerBusinessRegistrationVerification.unavailable();
        }
    }

    private SellerBusinessRegistrationVerification toVerification(
            NtsBusinessRegistrationResponse response,
            String businessNumber
    ) {
        if (response == null || !OK_STATUS_CODE.equals(response.statusCode())) {
            return SellerBusinessRegistrationVerification.unavailable();
        }

        if (response.data() == null || response.data().isEmpty()) {
            return SellerBusinessRegistrationVerification.invalid();
        }

        NtsBusinessRegistrationData registrationData = response.data().getFirst();
        if (!businessNumber.equals(registrationData.businessNumber())) {
            return SellerBusinessRegistrationVerification.invalid();
        }

        if (!VALID_REGISTRATION_CODE.equals(registrationData.valid())) {
            return SellerBusinessRegistrationVerification.invalid();
        }

        NtsBusinessStatusData statusData = registrationData.status();
        if (statusData == null || !ACTIVE_BUSINESS_STATUS_CODE.equals(statusData.businessStatusCode())) {
            return SellerBusinessRegistrationVerification.invalid();
        }

        return SellerBusinessRegistrationVerification.available();
    }

    /**
     * 국세청 진위확인 API 요청 body다.
     */
    private record NtsBusinessRegistrationRequest(
            // 진위확인 대상 사업자 정보 목록. 가입 검증에서는 1개만 보낸다.
            List<NtsBusinessRegistrationPayload> businesses
    ) {
    }

    /**
     * 국세청 진위확인 API의 사업자별 요청 데이터다.
     */
    private record NtsBusinessRegistrationPayload(
            // 사업자등록번호.
            @JsonProperty("b_no")
            String businessNumber,
            // 개업일자. yyyyMMdd 형식으로 전송한다.
            @JsonProperty("start_dt")
            String businessStartDate,
            // 대표자명. 판매자 가입 요청의 이름을 사용한다.
            @JsonProperty("p_nm")
            String representativeName
    ) {

        private static NtsBusinessRegistrationPayload of(
                String businessNumber,
                String representativeName,
                LocalDate businessStartDate
        ) {
            return new NtsBusinessRegistrationPayload(
                    businessNumber,
                    businessStartDate.format(BUSINESS_START_DATE_FORMATTER),
                    representativeName
            );
        }
    }

    /**
     * 국세청 진위확인 API 응답 body다. API 스펙에 맞춰 snake_case 필드명을 그대로 사용한다.
     */
    private record NtsBusinessRegistrationResponse(
            // API 처리 결과 코드. OK일 때만 data를 가입 검증에 사용한다.
            @JsonProperty("status_code")
            String statusCode,
            // 진위확인에 성공한 사업자 수.
            @JsonProperty("valid_cnt")
            Integer validCount,
            // 요청한 사업자 정보 개수.
            @JsonProperty("request_cnt")
            Integer requestCount,
            // 사업자별 진위확인 결과 목록.
            List<NtsBusinessRegistrationData> data
    ) {
    }

    /**
     * 국세청 진위확인 API의 사업자별 검증 결과다.
     */
    private record NtsBusinessRegistrationData(
            // 조회된 사업자등록번호.
            @JsonProperty("b_no")
            String businessNumber,
            // 진위확인 결과 코드. 01이면 요청 정보가 국세청 정보와 일치한다.
            String valid,
            // 진위확인 결과 메시지.
            @JsonProperty("valid_msg")
            String validMessage,
            // 진위확인된 사업자의 현재 상태 정보.
            NtsBusinessStatusData status
    ) {
    }

    /**
     * 국세청 진위확인 응답에 포함되는 사업자 상태 데이터다.
     */
    private record NtsBusinessStatusData(
            // 사업자 상태명. 예: 계속사업자, 휴업자, 폐업자.
            @JsonProperty("b_stt")
            String businessStatus,
            // 사업자 상태 코드. 01이면 계속사업자다.
            @JsonProperty("b_stt_cd")
            String businessStatusCode
    ) {
    }
}
