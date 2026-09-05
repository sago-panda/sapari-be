package com.sapari.chat.application.protocol;

/**
 * 한 메시지가 수신자에게 <b>얼마나 보이는가</b>.
 *
 * <p>두 가지가 걸려 있고 <b>둘은 서로 다른 축이다</b> — 마스킹 전 원문을 보는 것과 발신자 이메일을 보는
 * 것. 지금은 같은 대상(방 주인·관리자)에게 함께 열려 있지만, 그 둘이 늘 같이 다녀야 할 이유는 없다.
 * 플래그가 아니라 이름으로 두는 이유가 그것이다 — "관리자는 원문은 보되 이메일은 안 본다"가 필요해지면
 * 여기 한 줄이 늘고 호출부 한 줄이 바뀔 뿐, 팩토리 시그니처를 협상하지 않아도 된다.
 *
 * <p><b>불리언 쌍으로 두지 않는다.</b> {@code OutboundMessage}는 열다섯 개 nullable 필드를 한 모양으로
 * 나르는 합집합이고, 하필 {@code senderEmail} 바로 뒤가 {@code displayMessage}다. 인자 자리에 불리언을
 * 늘어놓으면 한 칸 밀린 실수가 컴파일도 타입 검사도 통과하고, 그 실수의 결과가 <b>방 전원에게 발신자
 * 이메일을 본문으로 뿌리는 것</b>이다. 이름을 붙이면 그 자리가 사라진다.
 *
 * <p><b>{@link #MASKED}가 기본이다.</b> 넓은 쪽을 기본으로 두면 새 역할이 늘 때마다 조용히 노출된다.
 */
public enum ChatMessageVisibility {

    /** 마스킹된 본문만. 일반 시청자가 받는 것이고, 이것이 기본이다. */
    MASKED(false, false),

    /**
     * 마스킹 전 원문과 발신자 이메일. 방 주인과 관리자가 받는다.
     *
     * <p><b>이메일은 강퇴에 쓰이지 않는다.</b> 강퇴는 {@code targetUserId}와 {@code messageId}로 이뤄지고,
     * 모더레이션 경로 어디에서도 이 값을 읽지 않는다. 그럼에도 관리자에게 함께 주는 것은 제품 결정이다 —
     * 방 주인이 이미 보는 값이고 관리자를 그보다 좁게 둘 근거가 없다는 판단이었다.
     *
     * <p>⚠️ 그 결정이 안고 가는 것: 방 주인은 <b>자기 방 구매자</b>의 이메일을 보지만 관리자는 어느 방이든
     * 들어갈 수 있어 범위 제한이 없다. 그리고 <b>누가 언제 무엇을 봤는지에 대한 기록이 없다</b> — 팬아웃에
     * 감사 로그를 붙이면 메시지마다 비용이 붙어서다. 되짚을 수단이 필요해지면 팬아웃이 아니라 관리자
     * 세션의 <b>입장 시점</b>에 한 줄이 맞다(연결당 1회).
     */
    FULL(true, true);

    private final boolean originalMessage;
    private final boolean senderEmail;

    ChatMessageVisibility(boolean originalMessage, boolean senderEmail) {
        this.originalMessage = originalMessage;
        this.senderEmail = senderEmail;
    }

    /** 마스킹 전 원문을 싣는가. */
    public boolean showsOriginalMessage() {
        return originalMessage;
    }

    /** 발신자 이메일을 싣는가. */
    public boolean showsSenderEmail() {
        return senderEmail;
    }
}
