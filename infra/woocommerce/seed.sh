#!/bin/sh
# Seeds the local WooCommerce test store (B-05). Idempotent: safe to run on every `compose up`.
# Local-only credentials. Products and API keys are fixtures for development and tests.
set -eu

cd /var/www/html

echo "Waiting for WordPress files and wp-config.php..."
i=0
until [ -f wp-config.php ] && wp db check >/dev/null 2>&1; do
  i=$((i + 1))
  [ "$i" -gt 90 ] && { echo "WordPress not ready after 180 s" >&2; exit 1; }
  sleep 2
done

# Local only: WordPress delivers webhooks with wp_safe_remote_request, which refuses private addresses such as
# host.docker.internal. This must-use plugin lifts that for the local test store (ADR-0009). Never in production.
mkdir -p wp-content/mu-plugins
cat > wp-content/mu-plugins/operator-local-webhooks.php <<'PHP'
<?php
/* Plugin Name: Operator local webhooks (development only) */
add_filter('http_request_host_is_external', '__return_true');
PHP

# Version 2: the Inventory Guardian scenario product (document 03 §2, plan section F). Order dates are relative
# to the day the seed runs, so after a few days the scenario needs a fresh store (`down -v`).
seed_scenario() {
  SERUM_ID=$(wp wc product create --user=admin --porcelain \
    --name="Serum nawilżające" --type=variable --sku=SERUM --status=publish \
    --attributes='[{"name":"Pojemność","options":["30 ml"],"variation":true,"visible":true}]')
  SERUM_VARIATION_ID=$(wp wc product_variation create "$SERUM_ID" --user=admin --porcelain \
    --sku=SERUM-30 --regular_price=129.00 --manage_stock=true --stock_quantity=60 \
    --attributes='[{"name":"Pojemność","option":"30 ml"}]')
  # 49 one-unit orders over the last 7 full days in Europe/Warsaw (the time zone of the demo stores): 7 a day.
  # Stock is set to 60 afterwards, so completing the orders does not change the scenario's starting stock.
  SERUM_VARIATION_ID="$SERUM_VARIATION_ID" wp eval '
$variation = wc_get_product((int) getenv("SERUM_VARIATION_ID"));
$zone = new DateTimeZone("Europe/Warsaw");
$today = new DateTimeImmutable("today", $zone);
for ($day = 1; $day <= 7; $day++) {
  for ($n = 0; $n < 7; $n++) {
    $order = wc_create_order();
    $order->add_product($variation, 1);
    $order->set_date_created($today->modify("-$day days")->setTime(9 + $n, 15)->getTimestamp());
    $order->calculate_totals();
    $order->set_status("completed");
    $order->save();
  }
}
wc_update_product_stock($variation, 60, "set");
'
  echo "Scenario seeded: SERUM-30 (product $SERUM_ID, variation $SERUM_VARIATION_ID), stock 60, 49 orders."
}

if wp option get operator_seed_version >/dev/null 2>&1; then
  version=$(wp option get operator_seed_version)
  if [ "$version" = "1" ]; then
    seed_scenario
    wp option update operator_seed_version 2
  fi
  echo "Store already seeded (operator_seed_version=$(wp option get operator_seed_version))."
  exit 0
fi

wp core install --url="$WOO_URL" --title="Operator Test Store" \
  --admin_user=admin --admin_password=admin --admin_email=admin@shop.local --skip-email

wp option update timezone_string UTC
wp rewrite structure '/%postname%/' --hard

# WOOCOMMERCE_SOURCE may point to a local zip (offline installs); default is the GitHub release.
WOOCOMMERCE_SOURCE=${WOOCOMMERCE_SOURCE:-"https://github.com/woocommerce/woocommerce/releases/download/${WOOCOMMERCE_VERSION}/woocommerce.zip"}
wp plugin install "$WOOCOMMERCE_SOURCE" --activate

wp option update woocommerce_currency PLN
wp option update woocommerce_default_country PL
wp option update woocommerce_manage_stock yes
wp option update woocommerce_onboarding_profile '{"skipped":true}' --format=json
wp option update woocommerce_coming_soon no

# Variable product with three size variants and per-variant stock.
PRODUCT_ID=$(wp wc product create --user=admin --porcelain \
  --name="Koszulka Basic" --type=variable --sku=TSHIRT-BASIC --status=publish \
  --attributes='[{"name":"Rozmiar","options":["S","M","L"],"variation":true,"visible":true}]')

for spec in "S:TSHIRT-BASIC-S:18" "M:TSHIRT-BASIC-M:3" "L:TSHIRT-BASIC-L:0"; do
  size=${spec%%:*}; rest=${spec#*:}; sku=${rest%%:*}; qty=${rest#*:}
  wp wc product_variation create "$PRODUCT_ID" --user=admin --porcelain \
    --sku="$sku" --regular_price=79.00 --manage_stock=true --stock_quantity="$qty" \
    --attributes="[{\"name\":\"Rozmiar\",\"option\":\"$size\"}]" >/dev/null
done

# Simple product for the non-variant path.
wp wc product create --user=admin --porcelain \
  --name="Kubek Logo" --type=simple --sku=MUG-LOGO --status=publish \
  --regular_price=39.00 --manage_stock=true --stock_quantity=49 >/dev/null

# Deterministic read-only REST API key for local integration work (B-12).
wp eval '
global $wpdb;
$ck = "ck_local_operator_0000000000000000000000";
$cs = "cs_local_operator_0000000000000000000000";
$wpdb->insert($wpdb->prefix . "woocommerce_api_keys", array(
  "user_id" => 1,
  "description" => "Operator local (read)",
  "permissions" => "read",
  "consumer_key" => wc_api_hash($ck),
  "consumer_secret" => $cs,
  "truncated_key" => substr($ck, -7),
));
'

seed_scenario

wp option add operator_seed_version 2
echo "Seed complete: product $PRODUCT_ID with 3 variants, 1 simple product and the SERUM-30 scenario."
