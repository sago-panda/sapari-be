#!/usr/bin/env bash
set -euo pipefail

REGION="${AWS_REGION:-ap-northeast-2}"
INSTANCE_TYPE="${VERIFY_INSTANCE_TYPE:-t4g.nano}"
S3_TEST_URL="${S3_TEST_URL:-https://s3.ap-northeast-2.amazonaws.com/amazon-ssm-ap-northeast-2/latest/linux_arm64/amazon-ssm-agent.rpm}"
RUN_ID="$(date -u +%Y%m%d%H%M%S)-$$"
ROLE_NAME="sapari-network-verify-$RUN_ID"
PROFILE_NAME="$ROLE_NAME"
POLICY_NAME="write-verification-result"
RESULT_PREFIX="network-verification/$RUN_ID"
INSTANCE_IDS=()
ROLE_CREATED=false
PROFILE_CREATED=false
ROLE_ATTACHED=false

cleanup() {
  local cleanup_status=0
  set +e

  if ((${#INSTANCE_IDS[@]} > 0)); then
    echo "Terminating verification instances: ${INSTANCE_IDS[*]}"
    aws ec2 terminate-instances --region "$REGION" --instance-ids "${INSTANCE_IDS[@]}" --output text >/dev/null
    aws ec2 wait instance-terminated --region "$REGION" --instance-ids "${INSTANCE_IDS[@]}" || cleanup_status=1
  fi

  if [[ -n "${RESULT_BUCKET:-}" ]]; then
    aws s3 rm "s3://$RESULT_BUCKET/$RESULT_PREFIX/" --recursive --region "$REGION" >/dev/null || cleanup_status=1
  fi
  if [[ "$ROLE_ATTACHED" == true ]]; then
    aws iam remove-role-from-instance-profile --instance-profile-name "$PROFILE_NAME" --role-name "$ROLE_NAME" || cleanup_status=1
  fi
  if [[ "$PROFILE_CREATED" == true ]]; then
    aws iam delete-instance-profile --instance-profile-name "$PROFILE_NAME" || cleanup_status=1
  fi
  if [[ "$ROLE_CREATED" == true ]]; then
    aws iam delete-role-policy --role-name "$ROLE_NAME" --policy-name "$POLICY_NAME" || cleanup_status=1
    aws iam delete-role --role-name "$ROLE_NAME" || cleanup_status=1
  fi

  if ((cleanup_status == 0)); then
    echo "Temporary instances, result objects, and IAM resources removed."
  else
    echo "WARNING: Some temporary verification resources could not be removed." >&2
  fi
}
trap cleanup EXIT

for command in aws jq terraform; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "Required command not found: $command" >&2
    exit 1
  }
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$STACK_DIR"

PUBLIC_SUBNET_ID="$(terraform output -json public_subnet_ids | jq -r '.["ap-northeast-2a"]')"
PRIVATE_SUBNET_ID="$(terraform output -json private_subnet_ids | jq -r '.["ap-northeast-2a"]')"
SECURITY_GROUP_ID="$(terraform output -raw livekit_security_group_id)"
RESULT_BUCKET="$(aws ssm get-parameter --region "$REGION" --name /sapari/dev/media/bucket --query 'Parameter.Value' --output text)"
AMI_ID="$(aws ssm get-parameter \
  --region "$REGION" \
  --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
  --query 'Parameter.Value' \
  --output text)"

TRUST_POLICY='{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
WRITE_POLICY="{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:PutObject\",\"Resource\":\"arn:aws:s3:::$RESULT_BUCKET/$RESULT_PREFIX/*\"}]}"

aws iam create-role \
  --role-name "$ROLE_NAME" \
  --assume-role-policy-document "$TRUST_POLICY" \
  --tags Key=Project,Value=sapari-be Key=Environment,Value=live-validation Key=Stack,Value=network-verification Key=ManagedBy,Value=verification-script \
  >/dev/null
ROLE_CREATED=true
aws iam put-role-policy --role-name "$ROLE_NAME" --policy-name "$POLICY_NAME" --policy-document "$WRITE_POLICY"
aws iam create-instance-profile \
  --instance-profile-name "$PROFILE_NAME" \
  --tags Key=Project,Value=sapari-be Key=Environment,Value=live-validation Key=Stack,Value=network-verification Key=ManagedBy,Value=verification-script \
  >/dev/null
PROFILE_CREATED=true
aws iam add-role-to-instance-profile --instance-profile-name "$PROFILE_NAME" --role-name "$ROLE_NAME"
ROLE_ATTACHED=true

# IAM instance profile propagation is eventually consistent.
sleep 10

launch_instance() {
  local subnet_id="$1"
  local name="$2"
  local user_data="$3"

  aws ec2 run-instances \
    --region "$REGION" \
    --image-id "$AMI_ID" \
    --instance-type "$INSTANCE_TYPE" \
    --subnet-id "$subnet_id" \
    --security-group-ids "$SECURITY_GROUP_ID" \
    --iam-instance-profile "Name=$PROFILE_NAME" \
    --user-data "$user_data" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$name},{Key=Project,Value=sapari-be},{Key=Environment,Value=live-validation},{Key=Stack,Value=network-verification},{Key=ManagedBy,Value=verification-script}]" \
    --query 'Instances[0].InstanceId' \
    --output text
}

PUBLIC_USER_DATA="#!/bin/bash
STATUS=\$(curl --connect-timeout 10 --max-time 20 -sS -o /dev/null -w '%{http_code}' https://checkip.amazonaws.com || true)
printf '%s' \"\$STATUS\" | aws s3 cp - 's3://$RESULT_BUCKET/$RESULT_PREFIX/public-http-status' --region '$REGION'
"
PRIVATE_USER_DATA="#!/bin/bash
STATUS=\$(curl --connect-timeout 10 --max-time 20 -sS -o /dev/null -w '%{http_code}' '$S3_TEST_URL' || true)
printf '%s' \"\$STATUS\" | aws s3 cp - 's3://$RESULT_BUCKET/$RESULT_PREFIX/private-s3-http-status' --region '$REGION'
"

PUBLIC_INSTANCE_ID="$(launch_instance "$PUBLIC_SUBNET_ID" "sapari-network-verify-public" "$PUBLIC_USER_DATA")"
INSTANCE_IDS+=("$PUBLIC_INSTANCE_ID")
PRIVATE_INSTANCE_ID="$(launch_instance "$PRIVATE_SUBNET_ID" "sapari-network-verify-private-s3" "$PRIVATE_USER_DATA")"
INSTANCE_IDS+=("$PRIVATE_INSTANCE_ID")

echo "Public verification instance:  $PUBLIC_INSTANCE_ID"
echo "Private verification instance: $PRIVATE_INSTANCE_ID"
aws ec2 wait instance-running --region "$REGION" --instance-ids "${INSTANCE_IDS[@]}"

read_result() {
  local key="$1"
  local attempt result

  for attempt in $(seq 1 36); do
    result="$(aws s3 cp "s3://$RESULT_BUCKET/$RESULT_PREFIX/$key" - --region "$REGION" 2>/dev/null || true)"
    if [[ "$result" =~ ^[0-9]{3}$ ]]; then
      printf '%s' "$result"
      return 0
    fi
    sleep 5
  done

  echo "Timed out waiting for verification result: $key" >&2
  return 1
}

PUBLIC_STATUS="$(read_result public-http-status)"
PRIVATE_S3_STATUS="$(read_result private-s3-http-status)"

echo "Public internet HTTP status: $PUBLIC_STATUS"
echo "Private S3 HTTP status:      $PRIVATE_S3_STATUS"

[[ "$PUBLIC_STATUS" == "200" ]] || {
  echo "Public subnet internet verification failed." >&2
  exit 1
}
[[ "$PRIVATE_S3_STATUS" == "200" ]] || {
  echo "Private subnet S3 gateway endpoint verification failed." >&2
  exit 1
}

echo "Network verification passed."
