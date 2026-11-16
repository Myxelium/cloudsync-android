# Authentication

CloudSync talks only to the Nextcloud server the user names. Credentials and file bodies are not sent to any other service.

## Login Flow v2

1. `ServerUrls.normalize` accepts HTTPS URLs only. `http`, userinfo, and localhost are rejected. A bare host becomes `https://host`.
2. `OcsClient.probe` reads `/ocs/v2.php/cloud/capabilities` and requires an `ocs` document.
3. `LoginFlowClient` posts to `/index.php/login/v2` and opens the returned login page in a Custom Tab.
4. The app polls the endpoint. HTTP 404 means the user has not approved yet. HTTP 200 returns `loginName` and `appPassword`.
5. `OcsClient.preferAppPassword` calls `/ocs/v2.php/core/getapppassword`. A 200 response replaces the secret with the generated app password. A 403 means the secret is already an app password and is kept. A 401 rejects the login.
6. `/ocs/v2.php/cloud/user` supplies the DAV user id, which can differ from the login name.
7. The app password is encrypted with an Android Keystore AES-GCM key (`cloudsync-credentials`) and written under the app's credential directory. The plaintext password is not written to disk or to the log.

## App password

The same exchange runs when the user types a username and secret. If Nextcloud will mint an app password, the typed value is discarded after the exchange. If it returns 403, the typed value is already an app password and is the value that is stored.

## Disconnect

Disconnect calls `DELETE /ocs/v2.php/core/apppassword` and then deletes the local secret even if the server cannot be reached. Scheduled work for that account is cancelled. Local files that were already downloaded are not deleted.

## TLS

`HttpClients.create` uses OkHttp's default trust manager and hostname verifier. There is no custom `TrustManager` and no certificate bypass. The network security config sets `cleartextTrafficPermitted` to false.

## Expiry

HTTP 401 marks the account `AUTH_EXPIRED`, raises a notification when that preference is on, and stops retrying that operation. The user signs in again. Tokens are not written into diagnostic events; `LogRedactor` also strips authorization headers if one is ever passed to the log.
