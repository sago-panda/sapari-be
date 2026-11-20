-- SPR-99 의 소셜 가입은 제공자 프로필 이미지 URL 을 profile_image_key 에 그대로 넣었다.
-- 이 컬럼은 이제 object key 만 담고(ProfileImagePublicUrlResolver 가 URL 형태를 거부한다),
-- URL 이 남은 사용자는 조회 경로 전체가 실패한다. 기본 이미지로 되돌린다.
UPDATE user_schema.users
SET profile_image_key = NULL
WHERE profile_image_key ~* '^https?://';
