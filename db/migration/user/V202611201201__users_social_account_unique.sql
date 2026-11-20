-- 같은 소셜 계정의 콜백이 동시에 두 번 가입을 끝내면 (provider, provider_id) 가 같은 행이 둘 생기고,
-- 그 뒤로 findBySocialAccount 가 결과 둘을 받아 그 계정은 로그인할 수 없다. DB 에서 막는다.
-- 위반은 CustomerAuthService.completeSocialSignup 이 DUPLICATED_SIGNUP_INFO 로 바꾼다.
-- 로컬 가입(판매자)은 provider 가 NULL 이라 대상이 아니다.
-- 이 경합이 이미 일어난 DB 에는 중복 행이 있어 인덱스 생성이 실패한다. 어떤 행을 남길지는 사람이 정할
-- 일이라 자동으로 지우지 않고, 원인을 밝히며 멈춘다. 확인:
--   SELECT provider, provider_id, count(*) FROM user_schema.users
--   WHERE provider IS NOT NULL GROUP BY 1, 2 HAVING count(*) > 1;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM user_schema.users WHERE provider IS NOT NULL
               GROUP BY provider, provider_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'users 에 같은 (provider, provider_id) 행이 둘 이상 있습니다. 정리한 뒤 다시 실행하세요.';
    END IF;
END $$;

CREATE UNIQUE INDEX uk_users_provider_provider_id
    ON user_schema.users (provider, provider_id)
    WHERE provider IS NOT NULL;
