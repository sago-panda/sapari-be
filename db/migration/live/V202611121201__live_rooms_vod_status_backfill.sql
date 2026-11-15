-- SPR-159 vod_status 백필. 이 버전 전에는 vod_status 를 쓰는 코드가 없어 종료된 방이 전부 NONE 이다.
-- 다시보기 노출이 READY 를 요구하게 되므로, 지금 노출되던 방(종료 + 아카이브 있음, 레거시 hls_url 복사 아님)을
-- READY 로 올려 그대로 보이게 한다. 녹화 완료 여부는 확인할 수 없다 — 지금까지의 노출 기준을 유지할 뿐이다.
-- 조건이 자기 자신을 다시 확인하므로 여러 번 돌려도 결과가 같다. 롤아웃 중 구 replica 가 놓친 알림은
-- RecoverStuckReplaysService(같은 조건 + 종료 15분 경과)가 메우므로 수동 재실행은 없다. 이 백필은 배포 직후
-- 그 잡이 처음 돌기 전(최대 10분) 기존 다시보기가 숨는 공백을 막는다.
UPDATE live_schema.live_rooms
   SET vod_status = 'READY'
 WHERE status = 'ENDED'
   AND vod_status = 'NONE'
   AND hls_archive_url IS NOT NULL
   AND TRIM(hls_archive_url) <> ''
   AND (hls_url IS NULL OR hls_archive_url <> hls_url);
