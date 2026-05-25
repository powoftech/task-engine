#!/bin/sh
set -eu

if [ "$(id -u)" = "0" ]; then
  chown -R rabbitmq:rabbitmq /var/lib/rabbitmq

  if command -v su-exec >/dev/null 2>&1; then
    exec su-exec rabbitmq "$0" "$@"
  fi

  if command -v gosu >/dev/null 2>&1; then
    exec gosu rabbitmq "$0" "$@"
  fi

  echo "Neither su-exec nor gosu is available to switch to the rabbitmq user." >&2
  exit 1
fi

user="${RABBITMQ_DEFAULT_USER:-guest}"
pass="${RABBITMQ_DEFAULT_PASS:-guest}"
vhost="${RABBITMQ_DEFAULT_VHOST:-/}"

rabbitmq-server -detached

startup_attempts=0
until rabbitmqctl --timeout 10 await_startup >/dev/null 2>&1; do
  startup_attempts=$((startup_attempts + 1))
  if [ "$startup_attempts" -ge 60 ]; then
    echo "RabbitMQ did not start within the expected time." >&2
    exit 1
  fi
  sleep 2
done

if rabbitmqctl add_vhost "$vhost" >/dev/null 2>&1; then
  echo "Created RabbitMQ vhost '$vhost'."
fi

if rabbitmqctl -q list_users | awk '{print $1}' | grep -Fx "$user" >/dev/null 2>&1; then
  rabbitmqctl change_password "$user" "$pass"
else
  rabbitmqctl add_user "$user" "$pass"
fi

rabbitmqctl set_user_tags "$user" administrator
rabbitmqctl set_permissions -p "$vhost" "$user" ".*" ".*" ".*"

rabbitmqctl stop
exec "$@"
