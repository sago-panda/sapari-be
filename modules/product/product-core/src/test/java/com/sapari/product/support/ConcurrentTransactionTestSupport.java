package com.sapari.product.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 동시성 통합 테스트 공통 베이스(template method).
 *
 * <p>여러 작업을 각자 독립 트랜잭션·스레드로 동시에 실행하고, 작업 사이의 인터리빙을 {@link CyclicBarrier}로 강제할 수 있게 한다.
 * 서브클래스는 "무엇을 동시에 할지"(읽기 → 배리어 → 쓰기)만 {@link BarrierTask}로 기술하면 되고, 스레드 생성·트랜잭션 경계·배리어 대기·예외 수집은
 * 베이스가 처리한다.
 *
 * <p>베이스가 {@code @Transactional}(테스트 롤백)이므로, 워커 스레드에서 보이도록 셋업·정리는 {@link #inNewTransaction}으로 독립
 * 커밋한다. 워커 스레드는 바인딩된 트랜잭션이 없어 각자 새 트랜잭션·커넥션으로 커밋된다(메인 테스트 트랜잭션과 무관).
 */
public abstract class ConcurrentTransactionTestSupport extends AbstractRepositoryIntegrationTest {

    private static final long JOIN_TIMEOUT_SECONDS = 30;
    private static final long BARRIER_TIMEOUT_SECONDS = 20;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 한 워커가 수행할 작업. 전달받은 {@code awaitBarrier}를 호출하면 모든 워커가 그 지점에 도달할 때까지 대기한다(예: 읽기 직후 호출 →
     * "모두 읽은 뒤 모두 쓰기"를 강제해 lost update를 결정적으로 재현).
     */
    @FunctionalInterface
    protected interface BarrierTask {

        /**
         * 워커 본문을 실행한다.
         *
         * @param awaitBarrier 호출 시 모든 워커가 도달할 때까지 대기하는 배리어 동기화 지점
         * @throws Exception 워커 수행 중 발생한 예외(수집되어 {@link #runConcurrently}가 반환)
         */
        void run(Runnable awaitBarrier) throws Exception;
    }

    /**
     * 현재 테스트 트랜잭션을 정지(REQUIRES_NEW)하고 독립 트랜잭션에서 실행·커밋한다. 워커가 볼 수 있어야 하는 셋업·정리에 사용한다.
     *
     * @param work 독립 트랜잭션에서 실행할 작업
     * @return 작업 반환값
     */
    protected <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    /**
     * 부수효과만 있는 작업을 독립 트랜잭션에서 실행·커밋한다.
     *
     * @param work 독립 트랜잭션에서 실행할 작업
     */
    protected void inNewTransaction(Runnable work) {
        inNewTransaction(() -> {
            work.run();
            return null;
        });
    }

    /**
     * 주어진 작업들을 각자 독립 트랜잭션·스레드로 동시에 실행한다. 모든 스레드가 끝날 때까지 기다린 뒤, 각 작업이 던진 예외를 모아 반환한다(정상 종료면 빈 목록).
     *
     * <p>각 작업은 워커 스레드에서 새 트랜잭션으로 감싸여 실행되므로, 작업 안의 읽기·쓰기는 하나의 트랜잭션에 묶인다.
     *
     * @param tasks 동시에 실행할 작업들(각자 같은 {@link CyclicBarrier}를 공유)
     * @return 각 작업이 던진 예외 목록(정상 종료한 작업은 포함되지 않음)
     */
    protected List<Throwable> runConcurrently(List<BarrierTask> tasks) {
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (BarrierTask task : tasks) {
            threads.add(new Thread(() -> runInWorkerTransaction(task, barrier, errors)));
        }
        threads.forEach(Thread::start);
        threads.forEach(ConcurrentTransactionTestSupport::join);
        return errors;
    }

    /**
     * 수집된 예외들 중(또는 그 원인 사슬 중) 주어진 타입의 예외가 하나라도 있는지 판별한다.
     *
     * @param errors {@link #runConcurrently}가 반환한 예외 목록
     * @param type   찾을 예외 타입
     * @return 해당 타입(또는 그 하위 타입) 예외가 하나라도 있으면 {@code true}
     */
    protected boolean anyErrorIsInstanceOf(List<Throwable> errors, Class<? extends Throwable> type) {
        return errors.stream().anyMatch(error -> {
            for (Throwable current = error; current != null; current = current.getCause()) {
                if (type.isInstance(current)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * 한 작업을 워커 스레드의 독립 트랜잭션으로 감싸 실행하고, 본문/커밋 중 발생한 예외를 수집한다.
     */
    private void runInWorkerTransaction(BarrierTask task, CyclicBarrier barrier, List<Throwable> errors) {
        try {
            // 워커 스레드엔 바인딩된 트랜잭션이 없으므로 REQUIRED만으로 새 트랜잭션·커넥션이 열린다.
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                try {
                    task.run(() -> awaitBarrier(barrier));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (Throwable e) {
            errors.add(unwrap(e));
        }
    }

    /**
     * 본문에서 던진 예외를 트랜잭션 래퍼가 {@link IllegalStateException}으로 감쌌다면 원래 예외로 되돌린다(커밋 단계 예외는 그대로 둔다).
     */
    private static Throwable unwrap(Throwable e) {
        if (e instanceof IllegalStateException && e.getCause() != null) {
            return e.getCause();
        }
        return e;
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("배리어 대기 실패", e);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(JOIN_TIMEOUT_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("워커 스레드 종료 대기 중 인터럽트", e);
        }
    }
}
