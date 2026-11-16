# Nextcloud test server

Unit tests do not need a server. WebDAV and login tests use MockWebServer.

To exercise a real server, this Compose file starts one. It is for development. The admin password is a local default, not a production secret, and it is not used by the app unless you type it.

```yaml
services:
  nextcloud:
    image: nextcloud:31-apache
    ports:
      - "8088:80"
    environment:
      SQLITE_DATABASE: nextcloud
      NEXTCLOUD_ADMIN_USER: admin
      NEXTCLOUD_ADMIN_PASSWORD: admin
      NEXTCLOUD_TRUSTED_DOMAINS: localhost
```

`docker-compose.yml` in the repository root is that file. Start it with `docker compose up`. Create an app password under Personal settings, Security. Point CloudSync at `https://` only; the Compose port above is plain HTTP and the app will reject it. Put TLS in front of it, or use a server you already run with a normal certificate.

What to check by hand:

- Login Flow v2 and an app password both connect, and disconnect removes the account.
- Create, rename, move, copy, and delete a file from the browser.
- Sync one folder in each direction. Confirm an upload-only folder does not gain remote-only files, and a download-only folder does not upload an edit.
- Turn on Wi-Fi only, switch the device to mobile data, and confirm the folder waits.
- Edit the same line on the device and on the server. Confirm both originals remain and the conflict screen can save a merge.
- Kill the app during a transfer and open it again. The transfer should be queued, not forgotten.

There is no checked-in test that requires this server, so CI does not need the password.
