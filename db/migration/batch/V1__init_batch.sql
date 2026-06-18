-- Spring Batch 메타데이터 스키마.
-- JobRepository가 Job/Step 실행 상태를 저장하여 실행 이력 조회,
-- 낙관적 잠금, 실패 후 재시작을 지원한다.

-- JobInstance는 Job 실행의 논리적 식별자다.
-- JOB_NAME + JOB_KEY가 같으면 여러 번 실행/재시도되어도 같은 JobInstance로 본다.
CREATE TABLE BATCH_JOB_INSTANCE (
    JOB_INSTANCE_ID BIGINT NOT NULL PRIMARY KEY, -- JobInstance의 ID
    VERSION BIGINT, -- 낙관적 잠금 버전
    JOB_NAME VARCHAR(100) NOT NULL, -- 실행할 Job 이름
    JOB_KEY VARCHAR(32) NOT NULL, -- JobParameters로 생성한 JobInstance 식별 키
    CONSTRAINT JOB_INST_UN UNIQUE (JOB_NAME, JOB_KEY)
);

-- JobExecution은 하나의 JobInstance를 실제로 실행한 1회 시도다.
-- VERSION은 Spring Batch가 낙관적 잠금에 사용한다.
CREATE TABLE BATCH_JOB_EXECUTION (
    JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY, -- 실행된 JobExecution의 ID
    VERSION BIGINT, -- 낙관적 잠금 버전
    JOB_INSTANCE_ID BIGINT NOT NULL, -- 실행 대상 JobInstance의 ID
    CREATE_TIME TIMESTAMP NOT NULL, -- JobExecution 생성 시각
    START_TIME TIMESTAMP DEFAULT NULL, -- JobExecution 시작 시각
    END_TIME TIMESTAMP DEFAULT NULL, -- JobExecution 종료 시각
    STATUS VARCHAR(10), -- JobExecution 실행 상태
    EXIT_CODE VARCHAR(2500), -- JobExecution 종료 코드
    EXIT_MESSAGE VARCHAR(2500), -- JobExecution 종료 메시지
    LAST_UPDATED TIMESTAMP, -- JobExecution 마지막 갱신 시각
    CONSTRAINT JOB_INST_EXEC_FK FOREIGN KEY (JOB_INSTANCE_ID)
        REFERENCES BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
);

-- 실행에 사용된 JobParameters를 저장한다.
-- IDENTIFYING은 JobInstance 식별에 참여하는 파라미터 여부를 표시한다.
CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
    JOB_EXECUTION_ID BIGINT NOT NULL, -- 파라미터가 속한 JobExecution의 ID
    PARAMETER_NAME VARCHAR(100) NOT NULL, -- JobParameter 이름
    PARAMETER_TYPE VARCHAR(100) NOT NULL, -- JobParameter 타입
    PARAMETER_VALUE VARCHAR(2500), -- JobParameter 값
    IDENTIFYING CHAR(1) NOT NULL, -- JobInstance 식별 참여 여부
    CONSTRAINT JOB_EXEC_PARAMS_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

-- StepExecution은 각 JobExecution에 속한 Step별 진행 상태와 처리 카운터를 저장한다.
CREATE TABLE BATCH_STEP_EXECUTION (
    STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY, -- 실행된 StepExecution의 ID
    VERSION BIGINT NOT NULL, -- 낙관적 잠금 버전
    STEP_NAME VARCHAR(100) NOT NULL, -- 실행된 Step 이름
    JOB_EXECUTION_ID BIGINT NOT NULL, -- Step이 속한 JobExecution의 ID
    CREATE_TIME TIMESTAMP NOT NULL, -- StepExecution 생성 시각
    START_TIME TIMESTAMP DEFAULT NULL, -- StepExecution 시작 시각
    END_TIME TIMESTAMP DEFAULT NULL, -- StepExecution 종료 시각
    STATUS VARCHAR(10), -- StepExecution 실행 상태
    COMMIT_COUNT BIGINT, -- 커밋 횟수
    READ_COUNT BIGINT, -- 읽은 item 수
    FILTER_COUNT BIGINT, -- 필터링된 item 수
    WRITE_COUNT BIGINT, -- 쓴 item 수
    READ_SKIP_COUNT BIGINT, -- 읽기 skip 수
    WRITE_SKIP_COUNT BIGINT, -- 쓰기 skip 수
    PROCESS_SKIP_COUNT BIGINT, -- 처리 skip 수
    ROLLBACK_COUNT BIGINT, -- 롤백 횟수
    EXIT_CODE VARCHAR(2500), -- StepExecution 종료 코드
    EXIT_MESSAGE VARCHAR(2500), -- StepExecution 종료 메시지
    LAST_UPDATED TIMESTAMP, -- StepExecution 마지막 갱신 시각
    CONSTRAINT JOB_EXEC_STEP_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

-- Step 단위 ExecutionContext.
-- reader 위치처럼 실패/재시작 시 복원해야 하는 Step 상태를 저장한다.
CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT (
    STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY, -- ExecutionContext가 속한 StepExecution의 ID
    SHORT_CONTEXT VARCHAR(2500) NOT NULL, -- 짧은 형태의 Step ExecutionContext
    SERIALIZED_CONTEXT TEXT, -- 직렬화된 전체 Step ExecutionContext
    CONSTRAINT STEP_EXEC_CTX_FK FOREIGN KEY (STEP_EXECUTION_ID)
        REFERENCES BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
);

-- Job 단위 ExecutionContext.
-- Job 전체에서 공유되는 재시작 가능 상태를 저장한다.
CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT (
    JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY, -- ExecutionContext가 속한 JobExecution의 ID
    SHORT_CONTEXT VARCHAR(2500) NOT NULL, -- 짧은 형태의 Job ExecutionContext
    SERIALIZED_CONTEXT TEXT, -- 직렬화된 전체 Job ExecutionContext
    CONSTRAINT JOB_EXEC_CTX_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

-- Spring Batch는 관련 메타데이터 행을 넣기 전에 ID를 알아야 하므로
-- DB 자동 증가 키 대신 시퀀스를 사용한다.
-- ID를 먼저 가져오고, 그 ID로 객체와 FK 관계를 맞춰 저장하는 방식이다.
-- BIGINT 최댓값이 9223372036854775807 이므로 시퀀스도 이 값을 넘지 않게 한다.
CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ
    MAXVALUE 9223372036854775807
    NO CYCLE;

CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ
    MAXVALUE 9223372036854775807
    NO CYCLE;

CREATE SEQUENCE BATCH_JOB_INSTANCE_SEQ
    MAXVALUE 9223372036854775807
    NO CYCLE;
