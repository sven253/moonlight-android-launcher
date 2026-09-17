# Moonlight Launcher

Eine kleine Android-TV-App, die auf Knopfdruck den Gaming-PC aufweckt und danach
Moonlight mit einem festgelegten PC und einer festgelegten App startet.

Ablauf bei jedem Start:

0. Optional: WireGuard-Tunnel aufbauen (siehe Abschnitt 3).
1. TCP-Verbindungsversuch zum PC (Standard: Port 47989).
2. Antwortet er, geht es sofort zu Schritt 4.
3. Antwortet er nicht: SSH zum WoL-Relay, dort den Weck-Befehl ausführen,
   danach im Sekundentakt warten, bis der Port offen ist.
4. Moonlight über `com.limelight.ShortcutTrampoline` starten, App beenden.

Es gibt zwei Kacheln auf dem TV-Startbildschirm: **Moonlight Launcher** und
**Moonlight Launcher – Einstellungen**.

---

## 1. Bauen

### Variante A: Android Studio

Projektordner öffnen, Gradle-Sync abwarten, dann
`Build → Build Bundle(s) / APK(s) → Build APK(s)`.
Das Ergebnis liegt unter `app/build/outputs/apk/debug/app-debug.apk`.

### Variante B: GitHub Actions

Projekt in ein GitHub-Repository pushen. Der Workflow unter
`.github/workflows/build.yml` läuft automatisch und legt die APK als Artefakt
`pc-start-debug-apk` ab. Ohne Push lässt er sich im Tab *Actions* auch manuell
über *Run workflow* starten.

### Installieren

APK auf die TV-Box bringen (z. B. per `adb install app-debug.apk`, USB-Stick und
Dateimanager, oder Send-Files-to-TV). Installation aus unbekannten Quellen muss
in den Android-Einstellungen erlaubt sein.

---

## 2. Was vorher stimmen muss

* Der PC ist in Moonlight auf demselben Gerät bereits **eingerichtet und
  gepairt**. Diese App startet nur eine bestehende Moonlight-Konfiguration, sie
  ersetzt das Pairing nicht.
* Auf dem PC startet Sunshine bzw. GameStream nach dem Hochfahren automatisch —
  sonst öffnet sich der Prüfport nie und die App läuft in den Timeout.
* Das Relay ist per SSH erreichbar und `wol-relay.sh` liegt dort.

---

## 3. WireGuard-Tunnel (optional)

Wenn der PC von unterwegs gestreamt werden soll, kann der Launcher vorher einen
Tunnel der offiziellen WireGuard-App aufbauen. Ein eigener VPN-Stack steckt nicht
in der App: sie schickt WireGuard nur einen Broadcast und wartet, bis ein
VPN-Interface da ist.

Drei Modi stehen unter *Einstellungen -> VPN-Tunnel* zur Wahl:

- **Aus** — Verhalten wie bisher.
- **Automatisch** — erst der normale Erreichbarkeitstest; erst wenn der PC nicht
  antwortet, wird der Tunnel aufgebaut und erneut geprüft. Zu Hause kostet das
  keine Zeit.
- **Immer** — der Tunnel wird vor allem anderen aufgebaut. Sinnvoll, wenn auch
  das WoL-Relay nur über das VPN erreichbar ist.

### Was in WireGuard eingestellt sein muss

1. In der WireGuard-App unter *Einstellungen* muss **Fernsteuerung durch andere
   Apps** aktiviert sein. Ohne das verwirft WireGuard den Broadcast kommentarlos.
2. Die Berechtigung `CONTROL_TUNNELS` muss erteilt sein. Der Launcher fragt beim
   ersten Start danach; nachträglich geht es über *Einstellungen -> Berechtigung
   für WireGuard*.
3. Der Tunnel muss **einmal von Hand** in der WireGuard-App gestartet worden sein,
   damit die VPN-Zustimmung von Android vorliegt. Danach läuft es ohne Dialog.

Der Tunnelname muss exakt so geschrieben werden wie in WireGuard, inklusive
Groß- und Kleinschreibung.

### Hinweise

Der Tunnel bleibt nach dem Start von Moonlight bestehen — er wird ja zum Streamen
gebraucht. Ein automatisches Trennen gibt es nicht, weil der Launcher nicht
mitbekommt, wann Moonlight beendet wird.

Für das Streaming lohnt sich ein Split-Tunnel: in der Peer-Konfiguration bei
`AllowedIPs` nur das Heimnetz eintragen (etwa `192.168.178.0/24`) statt
`0.0.0.0/0`. Sonst läuft der gesamte Datenverkehr der TV-Box durch den Tunnel.

Führt der Tunnel den PC unter einer anderen Adresse, muss `pc_host` die Adresse
sein, die **über** das VPN gilt.

---

## 4. Werte für die Einstellungen finden

### PC-UUID und App-ID

In Moonlight auf dem PC-Symbol die Menütaste halten → *Details anzeigen*. Dort
stehen UUID und Name. Für die App-ID dasselbe auf der Kachel der gewünschten App.

Alternativ lassen sich statt UUID und ID auch **PC-Name** und **App-Name**
eintragen. Moonlight löst die dann selbst auf — dafür muss die App-Liste des PCs
allerdings schon einmal geladen worden sein, sonst schlägt der Start fehl.
UUID und App-ID sind der robustere Weg.

### MAC-Adresse

Die des Ziel-PCs, in beliebiger Schreibweise (`aa:bb:cc:dd:ee:ff`,
`AA-BB-CC-DD-EE-FF` oder `aabbccddeeff`).

### Weck-Befehl

Standard ist `/usr/local/bin/wol-relay.sh %MAC%`. Der Platzhalter `%MAC%` wird
durch die eingetragene MAC-Adresse ersetzt.

### Alles auf einmal per Datei

Statt jedes Feld mit der Fernbedienung einzutippen, lässt sich die komplette
Konfiguration als JSON einlesen: `config.example.json` aus diesem Repository
anpassen, auf einen USB-Stick kopieren (oder per
`adb push config.json /sdcard/Download/` ablegen) und in der App unter
*Einstellungen → Einstellungen importieren* auswählen.

Felder, die in der Datei fehlen, bleiben unverändert — eine Datei mit nur zwei
Schlüsseln ändert also auch nur diese zwei. Schlüssel mit führendem Unterstrich
sind Kommentare und werden ignoriert.

Umgekehrt schreibt *Einstellungen exportieren* die aktuelle Konfiguration als
JSON heraus, allerdings ohne Passwort, Key und Passphrase. Praktisch, um am
Gerät grob einzurichten, die Datei am Rechner zu vervollständigen und wieder
einzulesen.

---

## 5. Empfohlene Absicherung des Relays

Die Zugangsdaten liegen verschlüsselt im App-Speicher (Android Keystore). Auf
einer TV-Box ohne Displaysperre ist das aber vor allem Verschleierung. Deshalb:
lieber einen eigenen SSH-Key anlegen, der auf dem Relay **nur** dieses eine
Script ausführen darf.

Auf dem Relay in `~/.ssh/authorized_keys`:

```
command="/usr/local/bin/wol-relay.sh $SSH_ORIGINAL_COMMAND",no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-pty ssh-ed25519 AAAA... tv-wol
```

Oder, wenn die MAC ohnehin fest ist, noch enger:

```
command="/usr/local/bin/wol-relay.sh aa:bb:cc:dd:ee:ff",no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-pty ssh-ed25519 AAAA... tv-wol
```

Im zweiten Fall ist der eingetragene Befehl in der App egal — das Relay führt
immer nur seinen festen Befehl aus. Ein gestohlener Key kann dann nichts weiter
als einen PC aufwecken.

Der private Key lässt sich in den Einstellungen einfügen oder über
*Key aus Datei importieren* von einem USB-Stick laden. Unterstützt werden
OpenSSH- und PEM-Format, inklusive ed25519 und ecdsa.

### Hostkey

Beim ersten Verbinden merkt sich die App den Fingerprint des Relays und lehnt
spätere Abweichungen ab. Die Prüfung läuft während des Schlüsselaustauschs, also
bevor irgendwelche Zugangsdaten übertragen werden. Nach einer Neuinstallation
des Relays den gespeicherten Fingerprint in den Einstellungen antippen, um ihn
zurückzusetzen.

---

## 6. Fehlersuche

**„Der PC hat sich nach N Sekunden nicht gemeldet."**
Der Weck-Befehl lief durch, aber Port 47989 antwortet nicht. Prüfen, ob Sunshine
als Dienst automatisch startet, und ob die Wartezeit für die Boot-Dauer reicht.

**„Die Anmeldung am Relay wurde abgelehnt."**
Benutzername, Passwort oder Key stimmen nicht. Bei Key-Anmeldung: passt der
öffentliche Teil in `authorized_keys`, und hat die Datei dort die richtigen
Rechte (`600`, Verzeichnis `700`)?

**„Der Streaming-Client wurde nicht gefunden."**
Der Paketname stimmt nicht. Offizielles Moonlight ist `com.limelight`; Forks
verwenden eigene Namen. Auslesbar per `adb shell pm list packages | grep -i lime`.

**„PC nicht gefunden" (Meldung von Moonlight selbst)**
Die UUID passt zu keinem in Moonlight eingerichteten PC. UUID neu ablesen oder
stattdessen den PC-Namen eintragen.

**Der Test meldet Exit-Code ungleich 0.**
Das Script auf dem Relay ist fehlgeschlagen. Die Fehlerausgabe im Testdialog
zeigt in der Regel, woran es liegt — häufig ein fehlendes `chmod +x` oder ein
falscher Pfad.

Mit `adb logcat -s LaunchActivity SecurePrefs` lassen sich zusätzlich die Logs
der App mitlesen.

---

## 7. Bekannte Grenzen

Auflösung, Bildrate und Bitrate lassen sich **nicht** pro Verknüpfung setzen.
Moonlight liest diese Werte beim Streamstart aus seinen eigenen Einstellungen
und nimmt sie nicht über den Intent entgegen; an die Einstellungsdatei kommt
eine fremde App nicht heran. Es gilt also immer das, was global in Moonlight
eingestellt ist.
