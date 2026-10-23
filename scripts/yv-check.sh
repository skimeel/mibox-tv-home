#!/usr/bin/env bash
# Try the YouVersion Platform verse of the day by hand. Not part of the app or the build.
# Reads YOUVERSION_APP_KEY from launcher/youversion.env (git-ignored) and never prints it.
#   scripts/yv-check.sh                     BSB (Bible id 3034)
#   scripts/yv-check.sh --version-id 111    another Bible id
# Needs curl and jq.
set -euo pipefail
set +x # never trace: the key would be printed

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT/launcher/youversion.env"
API=https://api.youversion.com
BIBLE=3034

while [ $# -gt 0 ]; do
  case "$1" in
    --version-id) BIBLE="${2:-}"; shift 2 || { echo "--version-id needs a number" >&2; exit 2; } ;;
    --version-id=*) BIBLE="${1#*=}"; shift ;;
    -h|--help) sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
done
case "$BIBLE" in ''|*[!0-9]*) echo "--version-id must be a number, e.g. 3034" >&2; exit 2 ;; esac

for tool in curl jq; do
  command -v "$tool" >/dev/null || { echo "$tool is not installed" >&2; exit 1; }
done

[ -f "$ENV_FILE" ] || { echo "Missing $ENV_FILE (one line: YOUVERSION_APP_KEY=<key>)" >&2; exit 1; }
KEY="$(sed -n 's/^[[:space:]]*YOUVERSION_APP_KEY[[:space:]]*=//p' "$ENV_FILE" | tail -n 1 | tr -d "\"' \t\r")"
[ -n "$KEY" ] || { echo "No YOUVERSION_APP_KEY= line in $ENV_FILE" >&2; exit 1; }
case "$KEY" in *[!A-Za-z0-9._~+/=-]*) echo "The key in $ENV_FILE has unexpected characters" >&2; exit 1 ;; esac

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# The header goes to curl through a file only this user can read, so the key never shows in `ps`.
umask 077
printf 'X-YVP-App-Key: %s\n' "$KEY" > "$TMP/key-header"
unset KEY

# get <path> <name>: saves the body as $TMP/<name>.body and sets STATUS (000 = no response).
get() {
  STATUS="$(curl -sS --max-time 20 -H "@$TMP/key-header" -H 'Accept: application/json' \
    -D "$TMP/$2.headers" -o "$TMP/$2.body" -w '%{http_code}' "$API$1" 2>"$TMP/$2.err" || true)"
  [ -n "$STATUS" ] || STATUS=000
}

# ok <name> <label>: true on 200 with a JSON body; otherwise explains the failure and returns false.
ok() {
  case "$STATUS" in
    200)
      jq -e . "$TMP/$1.body" >/dev/null 2>&1 && return 0
      echo "$2: HTTP 200 but the body is not JSON" ;;
    000) echo "$2: no response ($(head -c 200 "$TMP/$1.err"))" ;;
    204) echo "$2: HTTP 204, no content" ;;
    429)
      # Plain text, not JSON.
      echo "$2: HTTP 429 rate limited, Retry-After: $(sed -n 's/^[Rr]etry-[Aa]fter:[[:space:]]*//p' "$TMP/$1.headers" | tr -d '\r') s" ;;
    *) echo "$2: HTTP $STATUS: $(head -c 300 "$TMP/$1.body" | tr '\n' ' ')" ;;
  esac
  return 1
}

FAILED=0
echo "Bible id: $BIBLE"

# (a) Today's verse of the day, by day of the year in Melbourne.
DAY=$((10#$(TZ=Australia/Melbourne date +%j)))
echo "Day of the year (Australia/Melbourne): $DAY"
PASSAGE=""
get "/v1/verse-of-the-days/$DAY" votd
if ok votd "Verse of the day"; then
  PASSAGE="$(jq -r '.passage_id // empty' "$TMP/votd.body")"
  echo "Passage id: ${PASSAGE:-empty}"
else
  FAILED=1
fi

# (b) Its text in the chosen Bible.
case "$PASSAGE" in
  '') echo "Passage text: skipped, no passage id"; FAILED=1 ;;
  *[!A-Za-z0-9.+-]*) echo "Passage text: skipped, unexpected passage id"; FAILED=1 ;;
  *)
    get "/v1/bibles/$BIBLE/passages/$PASSAGE?format=text" passage
    if ok passage "Passage text"; then
      echo "Reference: $(jq -r '.reference // "empty"' "$TMP/passage.body")"
      echo "Text:"
      jq -r '.content // "empty"' "$TMP/passage.body"
      echo "Characters: $(jq -r '(.content // "") | length' "$TMP/passage.body")"
    else
      FAILED=1
    fi ;;
esac

# (c) The Bible's attribution fields.
get "/v1/bibles/$BIBLE" bible
if ok bible "Bible details"; then
  echo "Bible: $(jq -r '[.abbreviation, .title] | map(select(. != null)) | join(" · ")' "$TMP/bible.body")"
  echo "copyright: $(jq -r '(.copyright // "") | if (gsub("\\s"; "") == "") then "empty" else . end' "$TMP/bible.body")"
  echo "promotional_content: $(jq -r '(.promotional_content // "") | if (gsub("\\s"; "") == "") then "empty" else . end' "$TMP/bible.body")"
else
  FAILED=1
fi

# (d) The whole year's calendar: counts only.
get "/v1/verse-of-the-days" year
if ok year "Year calendar"; then
  echo "Year calendar entries: $(jq '.data | length' "$TMP/year.body")"
  echo "Entries that are ranges (passage id with a dash): $(jq '[.data[].passage_id | select(test("-"))] | length' "$TMP/year.body")"
  echo "More pages (next_page_token): $(jq -r 'if (.next_page_token // "") == "" then "no" else "yes" end' "$TMP/year.body")"
else
  FAILED=1
fi

exit "$FAILED"
