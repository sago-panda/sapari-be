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
     * 마스킹 전 원문과 발신자 이메일. <b>방 주인과 관리자가 같은 것을 받는다.</b>
     *
     * <p><b>제품 결정이다</b> — 관리자와 판매자는 채팅에서 같은 권한을 갖는다. 좁힐 근거가 없어서가 아니라
     * 같아야 한다고 정했다. 그래서 이 단계 하나에 둘을 함께 둔다.
     *
     * <p>그 결정이 뒤집힐 때를 위해 <b>두 축은 코드에서 갈라져 있다</b>. 관리자에게 원문만 주고 이메일은
     * 빼려면 {@code MODERATOR(true, false)} 한 줄을 더하고 팬아웃 분기를 그리로 보내면 된다 —
     * 팩토리 시그니처를 건드릴 일이 없다.
     *
     * <p>⚠️ <b>결정이 안고 가는 것 둘.</b> 하나는 이메일이 모더레이션에 <b>쓰이지 않는다</b>는 것이다 —
     * 강퇴는 {@code targetUserId}와 {@code messageId}로 이뤄지고 그 경로에서 이 값을 읽는 코드는 없다.
     * 다른 하나는 <b>범위가 다르다</b>는 것이다 — 방 주인은 자기 방 구매자만 보지만 관리자는 진행 중인
     * 어느 방이든 들어갈 수 있어 제한이 없다.
     *
     * <p>그 둘을 상쇄하는 것이 <b>관리자 입장 로그</b>다({@code ChatSessionRegistry}, 연결당 1회). 노출을
     * 줄이지는 못하지만 "누가 언제 어느 방을 봤는가"에는 답한다. 이 로그를 지우면 결정의 전제가 바뀐다.
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
