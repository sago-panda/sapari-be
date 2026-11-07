package com.sapari.chat.domain.rule;

import java.util.UUID;

import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;

/**
 * 채팅 전송·강퇴 권한을 판정하는 순수 정책. 상태·외부 I/O가 없어 단위 테스트만으로 전부 검증된다.
 *
 * <p>권한은 <b>계정 role</b>과 <b>방 소유(isRoomOwner)</b> 두 축으로 갈린다 — SELLER 계정도 남의
 * 라이브에 시청자로 들어올 수 있어, role만으로 게이팅하면 타 방 공지 작성·구매자 이메일 누출(PII)을 허용한다.
 *
 * <p>가드(boolean)만 반환하고 예외는 던지지 않는다. 거부({@code false}) 시
 * {@code ChatPermissionDeniedException}을 던지는 책임은 호출 서비스에 있다(가드와 전이 분리).
 */
public final class ChatPermissionPolicy {

    /**
     * 전송 가능 여부.
     * <ul>
     *   <li>NORMAL: 회원(BUYER·SELLER·ADMIN) 허용, 소유 무관. GUEST 불가.
     *   <li>NOTICE: ADMIN 또는 자기 방 SELLER(isRoomOwner)만. 비-SELLER는 isRoomOwner가 참이어도 불가(방어).
     *   <li>SYSTEM: 사용자 전송 불가 — 서버 내부 생성 신호이므로 전 역할 거부(버그 방어).
     * </ul>
     */
    public boolean canSend(ChatRole role, boolean isRoomOwner, ChatMessageType type) {
        return switch (type) {
            case ChatMessageType.Normal n -> isMember(role);
            case ChatMessageType.Notice n -> role == ChatRole.ADMIN
                    || (role == ChatRole.SELLER && isRoomOwner);
            case ChatMessageType.System s -> false;
        };
    }

    /**
     * 강퇴 가능 여부.
     * <ul>
     *   <li>자기 자신은 강퇴 불가(역할·소유 무관) — 권한 규칙이 바뀌어도 self-kick은 막히도록 선차단.
     *   <li>ADMIN: 모든 방에서 ADMIN을 제외한 모든 역할 강퇴.
     *   <li>SELLER: 자기 방(kickerId == roomOwnerId)에서 ADMIN을 제외한 모든 참가자 강퇴 — 방문 SELLER도 내 방에선 시청자(isRoomOwner=false)이므로 강퇴 대상. ADMIN만 보호(플랫폼 운영자 ≥ 방주인).
     *   <li>그 외(BUYER·GUEST): 권한 없음.
     * </ul>
     * <p>없는 UUID와 비회원(GUEST)은 이 정책에 도달하지 않는다. 다만 그것을 막는 것은 사용자 조회가
     * 아니라 <b>증거 메시지 조회</b>다 — 호출 서비스가 강퇴 근거 메시지를 찾아 그 작성자가 강퇴 대상과
     * 같은지 확인하는데, 없는 사용자는 남긴 메시지가 없고 GUEST는 애초에 발화할 수 없어 어느 쪽도
     * 증거가 맞아떨어지지 않는다. <b>탈퇴 유예 상태는 다르다</b> — 증거는 메시지를 남긴 시점의 사실이라
     * 계정의 현재 상태를 보지 않고, 그건 의도다. 탈퇴를 신청하면 REST는 전부 막히지만 이미 열려 있는
     * 채팅 세션은 끊기지 않아, 플랫폼에서 로그아웃된 사람이 방에서 계속 말하는 상태가 실제로 생긴다.
     * 강퇴가 가장 필요한 순간이라 막지 않는다.
     */
    public boolean canKick(ChatRole kickerRole, UUID kickerId, UUID roomOwnerId,
                           ChatRole targetRole, UUID targetUserId) {
        if (kickerId.equals(targetUserId)) {
            return false;
        }
        return switch (kickerRole) {
            case ADMIN -> targetRole != ChatRole.ADMIN;
            case SELLER -> kickerId.equals(roomOwnerId) && targetRole != ChatRole.ADMIN;
            default -> false;
        };
    }

    private boolean isMember(ChatRole role) {
        return role == ChatRole.BUYER || role == ChatRole.SELLER || role == ChatRole.ADMIN;
    }

    /**
     * 이 세션이 마스킹 전 원문과 발신자 이메일을 받는가.
     *
     * <p><b>왜 여기인가.</b> 이 판정은 팬아웃(무엇을 보낼지)과 감사(특권을 썼는가) 두 곳에서 필요한데,
     * 전에는 각자 {@code isRoomOwner || role == ADMIN}을 손으로 적고 있었다. 두 모듈에 따로 있으면 특권
     * 뷰를 받는 역할이 하나 늘 때 한쪽만 따라간다 — 그리고 뒤처지는 쪽이 감사다. <b>원문과 이메일은 받는데
     * 흔적은 안 남는</b> 방향이라, 조용하고 나쁜 쪽으로 갈린다.
     *
     * <p>두 축이 갈라져 있는 것은 의도다. 소유는 방 단위이고(남의 방에 온 판매자는 시청자다) ADMIN은
     * 계정 단위라 소유를 묻지 않는다. 모더레이터가 원문을 봐야 하는 이유는 방 주인과 같다 — 무엇이
     * 오갔는지 못 보면 무엇을 끊을지 판단할 수 없다.
     *
     * <p><b>기본은 마스킹이고 이것이 참인 경우가 예외다.</b> 방향을 뒤집으면(기본을 원문으로 두면) 새
     * 역할이 늘 때마다 빠뜨림으로 노출된다.
     *
     * <p>노출 수준 enum({@code ChatMessageVisibility})을 돌려주지 않는 것은 그 타입이 application 계층에
     * 살기 때문이다 — 도메인이 그쪽을 의존하면 계층 규칙을 어긴다(ArchUnit이 잡는다). 판단은 여기서 하고,
     * 그 판단을 와이어 표현으로 옮기는 것은 그 표현이 사는 곳의 몫이다. 도메인 소유의 등급 타입을 새로
     * 두는 길도 있으나(원문/이메일을 따로 여는 축이 열린다) 지금은 불리언으로 충분하다.
     *
     * <p>정적 메서드로 두지 않는 것은 <b>이 클래스의 기존 규약</b> 때문이다 — 이미 인스턴스 빈으로 주입돼
     * 쓰이므로 정적을 섞으면 한 클래스에 호출 규약이 둘이 된다. 도메인에 정적 순수 함수를 두는 선례는
     * 따로 있다({@code ChatBanTier.of}).
     */
    public boolean seesUnmaskedContent(ChatRole role, boolean isRoomOwner) {
        return isRoomOwner || role == ChatRole.ADMIN;
    }

    /**
     * 이 세션이 <b>남의 방에서</b> 특권 뷰를 쓰고 있는가 — 감사에 남길 대상인지의 판정.
     *
     * <p>{@link #seesUnmaskedContent}와 한 자리에 두는 이유가 이것이다. 특권 뷰를 받는 역할이 늘면 이 판정도
     * 함께 참이 되어, 노출과 흔적이 갈리지 않는다.
     *
     * <p>자기 방을 보는 것은 특권 사용이 아니다. 오늘 그 조합은 만들어지지 않지만({@code ChatSession}이
     * {@code ADMIN + isRoomOwner}를 거부한다) 그 불변식이 풀리는 날 이 조건이 곧바로 옳은 동작이 된다.
     */
    public boolean usesPrivilegedViewInSomeoneElsesRoom(ChatRole role, boolean isRoomOwner) {
        return seesUnmaskedContent(role, isRoomOwner) && !isRoomOwner;
    }

    /**
     * 밴을 풀 수 있는가 — <b>ADMIN 만이다.</b>
     *
     * <p>{@link #canKick}과 축이 다르다. 강퇴는 방 안의 일이라 방 주인(SELLER)에게 열려 있지만,
     * 밴은 플랫폼 전역이라 방 주인이 자기 방에서 겪은 일로 전역 제재를 풀 자리가 없다. 특히
     * 자동 승격은 <b>서로 다른 판매자 3·6·9명</b>의 독립된 판단으로 걸리는데, 그중 한 명이 혼자
     * 되돌릴 수 있으면 그 임계가 요구한 독립성이 무의미해진다.
     *
     * <p>{@code roomOwnerId}를 받지 않는 것이 그 사실의 표현이다 — 방을 보지 않으므로 어느 방에서
     * 눌렀는지가 판정을 바꿀 수 없다. 서명에 그 자리를 두면 다음 사람이 "자기 방이면" 가지를 더하게 된다.
     *
     * <p>자기 밴 해제도 막힌다. ADMIN 은 애초에 밴 대상이 아니지만({@code canKick}이 ADMIN 을
     * 보호한다) 수동 밴 경로가 생기면 그 전제가 깨지므로, 그때 이 자리에 자기 대상 검사를 더해야 한다 —
     * 지금은 대상 id 를 받지 않아 그 검사를 <b>할 수 없다</b>는 것이 이 javadoc 의 요점이다.
     */
    public boolean canReleaseBan(ChatRole actorRole) {
        return actorRole == ChatRole.ADMIN;
    }

    /**
     * 강퇴 집행 명단을 정본에서 다시 세울 수 있는가 — <b>ADMIN 만이다.</b>
     *
     * <p>{@link #canReleaseBan}과 값이 같고 <b>이유는 다르다.</b> 그쪽은 "판매자 한 명이 3·6·9명의
     * 판단을 되돌릴 수 없다" 이고, 이쪽은 <b>이 조치를 필요로 하는 사람이 누구인가</b>다. 재적재가
     * 필요하다는 신호는 {@code KickStoreCorruptedException} 로그이고 그 로그를 읽는 사람은 플랫폼
     * 운영자다 — 판매자는 자기 방의 강퇴가 조용히 꺼진 것을 알 방법이 없으므로, 그에게 권한을 줘도
     * 쓸 계기가 없다. 두 판정을 하나로 합치지 않는 이유가 이것이다: 값이 갈릴 날의 근거가 서로 다르다.
     *
     * <p>이 조치 자체는 <b>과다 차단을 새로 만들지 못한다</b> — 입력이 정본이라 정본에 없는 사람을
     * 올릴 수 없다. 그래서 위험이 아니라 가청성이 권한의 기준이 된다.
     */
    public boolean canReconcileKickList(ChatRole actorRole) {
        return actorRole == ChatRole.ADMIN;
    }

    /**
     * 욕설 사전을 고칠 수 있는가 — <b>ADMIN 만이다.</b>
     *
     * <p>위 둘과 값이 같고 <b>이유는 또 다르다.</b> 이쪽 근거는 <b>영향 범위</b>다. 강퇴와 밴은
     * 한 사람에게 붙지만 사전은 <b>전 방 공용</b>이라, 한 줄이 플랫폼 전체의 마스킹을 바꾼다 —
     * 판매자에게 열면 자기 방송에서 쓰이는 말을 전 플랫폼에서 막거나 풀 수 있다.
     *
     * <p>그리고 양방향이 다 위험하다. 더하는 쪽은 정상어를 걸어 오탐을 만들고(그 복구가 또 이
     * 경로다), 빼는 쪽은 막혀 있던 욕설을 푼다. 강퇴 재적재처럼 "입력이 정본이라 새로 해로울 수
     * 없다" 가 성립하지 않는다.
     *
     * <p><b>조회도 같은 판정을 쓴다.</b> 목록은 "플랫폼이 무엇을 막는가" 의 전체이고, 우회하려는
     * 사람에게는 어떤 표기가 사전에 없는지를 알려 주는 값이다. 쓰기와 읽기를 가르지 않는 이유가
     * 그것이다 — 읽기 쪽이 더 느슨해질 근거가 없다.
     */
    public boolean canManageFilterWords(ChatRole actorRole) {
        return actorRole == ChatRole.ADMIN;
    }

    /**
     * 방의 종료 마커를 해제할 수 있는가 — <b>자기 방 SELLER 또는 ADMIN</b>.
     *
     * <p>위 셋과 달리 ADMIN 전용이 <b>아니다</b>. 근거는 <b>누가 손해를 보는가</b>다 — 마커가
     * 잘못 걸리면 그 방의 방송이 통째로 막히고, 그 손해를 보는 사람이 방 주인이다. 그리고 이
     * 조치는 {@code canReleaseBan} 처럼 남의 판단을 되돌리지 않는다: 해제가 통하는 것은 방이
     * <b>아직 진행 중</b>일 때뿐이고(호출 서비스가 그걸 따로 검증한다) 그 조건에서 마커는 애초에
     * 틀린 마커다.
     *
     * <p>두 축이 필요한 이유는 {@link #canSend} 의 NOTICE 와 같다 — SELLER 계정도 남의 방에
     * 시청자로 들어오므로 role 만으로 열면 남의 방 마커를 지울 수 있다.
     *
     * <p>이 판정은 {@code ClearRoomEndedMarkerService} 안에 인라인으로 있었다. 값은 같고
     * 자리만 옮긴 것이다 — chat 의 인가 판정을 한 클래스가 들고 있어야 특권 역할이 하나 늘 때
     * 정책 테스트의 전수가 그걸 잡는다.
     */
    public boolean canClearRoomEndedMarker(ChatRole actorRole, boolean isRoomOwner) {
        return actorRole == ChatRole.ADMIN || (actorRole == ChatRole.SELLER && isRoomOwner);
    }
}
