/**
 * The privacy policy, at /privacy - the URL given to Samsung's Seller Office, which requires one for
 * an app that sends a device identifier anywhere.
 *
 * Every statement here was checked against the code rather than written from a template: what the
 * activation service stores is schema.sql and the /api/activate route; what the Samsung app sends
 * is web/src/platform/identity.ts and shared/activation.ts; the Android app's is
 * DeviceActivationClient.kt and DeviceIdentity.kt; what stays on the television is the apps' own
 * local storage. If any of that changes, this page has to change with it - a policy that says less
 * than the app does is worse than none.
 */
const SUPPORT_EMAIL = '4kplustv.support@gmail.com';
const UPDATED = '26 September 2026';

// Shared by the English page and its Korean translation, so the two cannot drift apart in look.
const STYLE = `<style>
  body { margin: 0; background: #041a2c; color: #e8f1f7; font: 17px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, "Malgun Gothic", "Apple SD Gothic Neo", sans-serif; }
  main { max-width: 760px; margin: 0 auto; padding: 40px 22px 64px; }
  h1 { font-size: 30px; margin: 0 0 4px; }
  h2 { font-size: 21px; margin: 34px 0 8px; color: #9fd4ef; }
  p, li { color: #d3e3ee; }
  ul { padding-left: 22px; }
  .updated { color: #8fb0c4; margin: 0 0 26px; }
  .summary { background: #0b2a43; border: 1px solid #1f4d6d; border-radius: 12px; padding: 16px 20px; }
  .lang { float: right; font-size: 15px; }
  a { color: #7fd0ff; }
  code { background: #0b2a43; padding: 1px 6px; border-radius: 5px; }
</style>`;

function privacyPage() {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>4K Plus TV Player - Privacy Policy</title>
${STYLE}
</head>
<body>
<main>
<a class="lang" href="/privacy/ko" lang="ko">한국어</a>
<h1>Privacy Policy</h1>
<p class="updated">4K Plus TV Player &middot; Last updated ${UPDATED}</p>

<div class="summary">
<p><strong>In short:</strong> 4K Plus TV Player is a player for a TV service you already have. It
shows no ads, contains no analytics or tracking, and sells nothing about you. The only thing it
sends to us is an anonymous code for your television, so that a playlist can be assigned to it.
Your logins, favourites and viewing history stay on your television.</p>
</div>

<h2>Who we are</h2>
<p>This policy covers the 4K Plus TV Player apps for Samsung televisions and Android TV, and the
activation service they use. Questions and requests: <a href="mailto:${SUPPORT_EMAIL}">${SUPPORT_EMAIL}</a>.</p>

<h2>What leaves your television, and where it goes</h2>
<ul>
<li><strong>To us - a device code and a device key.</strong> The app shows a device code (written
like a network address, for example <code>26:B4:11:22:33:44</code>) and a six-digit device key, and
sends both to our activation service to ask whether a playlist has been assigned to this
television. On a Samsung television the code is made from the television's device ID (DUID) by a
one-way calculation, so the DUID itself cannot be recovered from it; if the television does not
provide a DUID, its network MAC address is used instead. On Android TV the code is made the same
way from the Android device ID. We store the code, the key, and the times the television was first
and last seen.</li>
<li><strong>To us - an assigned playlist, only if one is assigned.</strong> If you or your reseller
assign a playlist to your device code, we store that playlist's name and its address, or its server,
username and password, and send them to your television when it asks. They are entered by whoever
assigns the playlist; the app does not upload anything you type.</li>
<li><strong>To your TV provider.</strong> Channels, films and series come directly from the provider
you (or your reseller) set up. The app sends your provider login to that provider's server, as any
player for that service must. We do not receive it. The provider's own privacy policy applies to
what it does with your requests.</li>
<li><strong>To image servers.</strong> Posters and channel logos are loaded from the addresses your
provider's catalogue lists, often a film database such as TMDB. Those servers see your television's
IP address, as any website would.</li>
<li><strong>To YouTube, for trailers.</strong> When you press Trailer, the Samsung app plays it
through YouTube's embedded player on a page on our server, which is given only the YouTube video ID;
the Android app opens the YouTube app. Google's privacy policy applies to YouTube.</li>
<li><strong>Server logs.</strong> Our service runs on Render in the United States. Like any web
server it may keep short-lived technical logs of requests, including IP addresses, to operate and
secure the service. We do not use them to identify or profile you.</li>
</ul>

<h2>What stays on your television</h2>
<p>Your provider login if you type it in, your saved playlists, favourites, recently watched
channels and titles, playback positions, settings, and your parental-control PIN (stored only as a
one-way hash, never as the PIN itself). These are kept in the app's storage on the television and
are not sent to us. Uninstalling the app, or clearing its data, removes them.</p>

<h2>What we do not do</h2>
<ul>
<li>No advertising, and no advertising identifiers.</li>
<li>No analytics, crash-reporting or tracking services.</li>
<li>No selling, renting or sharing of any information with third parties for marketing.</li>
<li>No content of our own: the app plays only what your provider supplies.</li>
</ul>

<h2>Keeping and deleting</h2>
<p>A device code stays in the activation service until it is removed. To have yours, and any
playlist assigned to it, deleted, email <a href="mailto:${SUPPORT_EMAIL}">${SUPPORT_EMAIL}</a> with
the device code shown on the app's Home screen. We will delete it and confirm.</p>

<h2>Children</h2>
<p>The app is not directed at children under 13, and we do not knowingly collect information from
them. It includes parental controls: categories can be hidden or locked behind a PIN.</p>

<h2>Changes</h2>
<p>If what the app sends changes, this page will change first, and the date at the top will say
when.</p>
</main>
</body>
</html>`;
}

/**
 * The same policy in Korean, at /privacy/ko - Seller Office requires a Korean URL as well as an
 * English one. A translation of privacyPage() above, section for section, and nothing more: any
 * change to what the English page says must be made here too, or the two will disagree about what
 * the app does. The English page is the one the facts were checked against.
 */
const UPDATED_KO = '2026년 9월 26일';

function privacyPageKo() {
  return `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>4K Plus TV Player - 개인정보 처리방침</title>
${STYLE}
</head>
<body>
<main>
<a class="lang" href="/privacy" lang="en">English</a>
<h1>개인정보 처리방침</h1>
<p class="updated">4K Plus TV Player &middot; 최종 수정일 ${UPDATED_KO}</p>

<div class="summary">
<p><strong>요약:</strong> 4K Plus TV Player는 이용자가 이미 가입한 TV 서비스를 재생하는 플레이어입니다.
광고를 표시하지 않으며, 분석 도구나 추적 기능이 없고, 이용자에 관한 어떠한 정보도 판매하지
않습니다. 앱이 저희에게 보내는 정보는 재생 목록을 TV에 연결하기 위한 익명의 기기 코드뿐입니다.
로그인 정보, 즐겨찾기, 시청 기록은 TV에만 저장됩니다.</p>
</div>

<h2>운영자 정보</h2>
<p>본 방침은 삼성 TV용 및 Android TV용 4K Plus TV Player 앱과 앱이 사용하는 활성화 서비스에
적용됩니다. 문의 및 요청: <a href="mailto:${SUPPORT_EMAIL}">${SUPPORT_EMAIL}</a></p>

<h2>TV 밖으로 전송되는 정보와 그 목적지</h2>
<ul>
<li><strong>저희에게 - 기기 코드와 기기 키.</strong> 앱은 기기 코드(네트워크 주소 형식, 예:
<code>26:B4:11:22:33:44</code>)와 6자리 기기 키를 화면에 표시하며, 이 TV에 재생 목록이
지정되었는지 확인하기 위해 두 값을 저희 활성화 서비스로 전송합니다. 삼성 TV에서는 이 코드가
TV의 기기 ID(DUID)로부터 단방향 계산을 통해 만들어지므로, 코드로부터 DUID를 복원할 수 없습니다.
TV가 DUID를 제공하지 않는 경우에는 대신 네트워크 MAC 주소가 사용됩니다. Android TV에서는 같은
방식으로 Android 기기 ID로부터 코드가 만들어집니다. 저희는 코드, 키, 그리고 해당 TV가 처음과
마지막으로 확인된 시각을 저장합니다.</li>
<li><strong>저희에게 - 지정된 재생 목록 (지정된 경우에만).</strong> 이용자 또는 판매점이 기기
코드에 재생 목록을 지정하면, 저희는 해당 재생 목록의 이름과 주소, 또는 서버·사용자 이름·비밀번호를
저장하고, TV가 요청할 때 이를 TV로 전송합니다. 이 정보는 재생 목록을 지정하는 사람이 입력하며,
앱은 이용자가 입력한 내용을 업로드하지 않습니다.</li>
<li><strong>이용자의 TV 서비스 제공업체에게.</strong> 채널, 영화, 시리즈는 이용자(또는 판매점)가
설정한 제공업체로부터 직접 전달됩니다. 앱은 해당 서비스의 모든 플레이어와 마찬가지로 제공업체
로그인 정보를 그 제공업체의 서버로 보냅니다. 저희는 이 정보를 받지 않습니다. 이용자의 요청을
제공업체가 어떻게 처리하는지는 해당 제공업체의 개인정보 처리방침을 따릅니다.</li>
<li><strong>이미지 서버에게.</strong> 포스터와 채널 로고는 제공업체의 목록에 표시된 주소(대개 TMDB와
같은 영화 데이터베이스)에서 불러옵니다. 여느 웹사이트와 마찬가지로, 해당 서버는 TV의 IP 주소를 알 수
있습니다.</li>
<li><strong>YouTube에게 (예고편).</strong> 예고편 버튼을 누르면, 삼성 앱은 저희 서버의 페이지에서
YouTube 임베디드 플레이어로 예고편을 재생하며, 이 페이지에는 YouTube 동영상 ID만 전달됩니다.
Android 앱은 YouTube 앱을 엽니다. YouTube에는 Google의 개인정보 처리방침이 적용됩니다.</li>
<li><strong>서버 로그.</strong> 저희 서비스는 미국의 Render에서 운영됩니다. 다른 웹 서버와 마찬가지로,
서비스 운영과 보안을 위해 IP 주소를 포함한 요청 기록을 단기간 보관할 수 있습니다. 저희는 이를
이용자를 식별하거나 분석하는 데 사용하지 않습니다.</li>
</ul>

<h2>TV에만 저장되는 정보</h2>
<p>직접 입력한 제공업체 로그인 정보, 저장된 재생 목록, 즐겨찾기, 최근 시청한 채널과 콘텐츠, 재생
위치, 설정, 그리고 자녀 보호 PIN(PIN 자체가 아닌 단방향 해시로만 저장). 이 정보는 TV의 앱 저장소에
보관되며 저희에게 전송되지 않습니다. 앱을 삭제하거나 앱 데이터를 지우면 함께 삭제됩니다.</p>

<h2>저희가 하지 않는 일</h2>
<ul>
<li>광고를 표시하지 않으며, 광고 식별자를 사용하지 않습니다.</li>
<li>분석, 충돌 보고, 추적 서비스를 사용하지 않습니다.</li>
<li>마케팅 목적으로 어떠한 정보도 제3자에게 판매, 대여, 공유하지 않습니다.</li>
<li>자체 콘텐츠가 없습니다. 앱은 이용자의 제공업체가 제공하는 콘텐츠만 재생합니다.</li>
</ul>

<h2>보관 및 삭제</h2>
<p>기기 코드는 삭제될 때까지 활성화 서비스에 보관됩니다. 이용자의 기기 코드와 그에 지정된 재생
목록의 삭제를 원하시면, 앱 홈 화면에 표시된 기기 코드를 적어
<a href="mailto:${SUPPORT_EMAIL}">${SUPPORT_EMAIL}</a>로 이메일을 보내 주십시오. 삭제 후
확인해 드립니다.</p>

<h2>아동</h2>
<p>본 앱은 만 13세 미만 아동을 대상으로 하지 않으며, 저희는 아동의 정보를 고의로 수집하지 않습니다.
앱에는 자녀 보호 기능이 있어, 카테고리를 숨기거나 PIN으로 잠글 수 있습니다.</p>

<h2>변경</h2>
<p>앱이 전송하는 정보가 바뀌면 이 페이지가 먼저 변경되며, 상단의 날짜가 변경 시점을 알려 드립니다.</p>
</main>
</body>
</html>`;
}

module.exports = { privacyPage, privacyPageKo, SUPPORT_EMAIL };
