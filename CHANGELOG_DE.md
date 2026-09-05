# Änderungen

Was jede Nexira-Version für die Person bedeutet, die den Launcher benutzt.
Gepflegt ab Version 2.3.4-beta4, das englische Original steht in
[CHANGELOG_EN.md](./CHANGELOG_EN.md).

Das Entwicklungsprotokoll ist ein eigenes Dokument:
[CHANGELOG.md](./CHANGELOG.md). Es nennt Klassen und Mechanismen und muss
nicht gelesen werden, um eine Version zu verstehen.

## [2.4.0] - 2026-09-05

2.4.0 ist das, worauf die Previews und Betas dieser Reihe hingearbeitet haben. Das meiste betrifft die Oberfläche: woraus eine Fläche besteht, ob die Zahl, die du einstellst, beim Pixel ankommt, und ob der Launcher aus dem Weg geht, sobald das Spiel läuft. Der Rest ist eine Liste von Dingen, die still nicht funktionierten, und einige davon konnten dich ein Konto, eine Sitzung oder etwas Getipptes kosten.

### Highlights
- **Dein Layout wird einmalig zurückgesetzt**. Eine Fläche wird jetzt über Zahlen beschrieben, die du setzen kannst, statt über Voreinstellungsnamen, die je drei Werte gleichzeitig bewegten. Die alten Beschreibungen lassen sich nicht ehrlich als die neuen lesen, also kehrt die Anordnung zu der zurück, die der Launcher mitbringt. Deine alte Datei bleibt unberührt auf der Platte liegen.
- **Die Darstellungsregler tun, was sie sagen**. Der Deckkraft-Regler bewegte vorher gar nichts: im dunklen Design zeichnete jede Fläche mit einem festen Wert, im hellen verweigerte sie Transparenz völlig. Die Unschärfe verwischt jetzt das, was wirklich hinter einer Fläche liegt, statt einer Kopie des Hintergrundbildes, eine Fläche malt kein hartes Quadrat mehr hinter ihre eigenen runden Ecken, und ihre Form kann ein Squircle, ein Stern oder ein Vieleck sein.
- **Ein Konto mit Zwei-Faktor-Anmeldung wird nicht mehr aus dem Spiel geworfen**. Das Öffnen des Launchers meldete dich erneut an, und SmartyCraft beendet bei jeder Anmeldung die vorherige Sitzung, also flog man Sekunden später mit einem Namensfehler aus dem laufenden Spiel, ohne dass irgendetwas auf dem Bildschirm die beiden verband.
- **Der Launcher geht aus dem Weg, egal wie du gestartet hast**. Das Ausblenden nach dem Start funktionierte nur vom klassischen Dashboard aus. Ein Pack aus der Bibliothek, von seiner eigenen Seite, nach einer Code-Abfrage oder nach einem Offline-Versuch ließ das Fenster vor dem Spiel stehen.
- **Das Fenster öffnet sofort in voller Größe**. Früher öffnete es klein und wurde erst nach dem bereits gezeichneten ersten Bild vergrößert, was rund zwei Sekunden Weiss um eine korrekt gezeichnete Ecke zeigte.
- **Ein Update zu installieren sieht nicht mehr wie ein Absturz aus**. Der Launcher blieb während des gesamten Austauschs auf dem Bildschirm, ohne noch zu zeichnen, und schrieb unter Linux dafür zwei vollständige Kopien seiner selbst. Jetzt geht das Fenster zuerst, und die Installation ist eine Umbenennung.
- **Die Update-Hinweise kommen in deiner Sprache**. Im Update-Dialog war alles übersetzt außer dem Einzigen, wofür man ihn öffnet. Die russischen und deutschen Änderungslisten gab es, und gelesen wurden sie von gar nichts. Sie sind jetzt ein eigenes Dokument, für dich geschrieben statt aus dem Entwicklerprotokoll geschnitten, und eine Korrektur braucht keine neue Version.
- **Mehr als drei Neuigkeiten, und schon vor der Anmeldung**. Die Leiste las aus einer Antwort, die drei trägt und immer trug, ein Widget mit Wunsch nach zwanzig zeigte also drei. Jetzt wird das Archiv der Website gelesen, seitenweise beim Scrollen. Weder die Neuigkeiten noch die Serverliste brauchen ein Konto, beide blieben aber bis zur Anmeldung leer, weil das Zertifikat des Servers nur im Anmeldeformular angenommen werden konnte.
- **Ein gewählter älterer Build wird auch installiert**. Die Versionsauswahl merkte sich den gewählten Build und lud den neuesten herunter, auf der Platte lag also nie das, was der Launcher zu haben glaubte.
- **Nichts nimmt dir mehr still etwas weg**. Das Zurücksetzen der Anpassung im Wiederherstellungsmodus löschte die Notizen und Checklisten, die du in Widgets getippt hattest, und fragte vorher nichts. Ein Launcher, der bei gesperrtem System-Schlüsselbund startete, löschte dein gespeichertes Konto endgültig. Ein Mod, dessen Download fehlschlug, meldete sich als installiert.
- **Deine Sitzung landet nicht im Log**. Eine Antwort, die der Launcher nicht lesen konnte, wurde mitsamt deiner uid und deinem Sitzungs-Token ins Log geschrieben und von dort in jedes Diagnosepaket, das du an den Support geschickt hast.

## [2.4.0-beta5] - 2026-08-05

2.4.0-beta5 ist ein kritisches Release. Es behebt Pruefungen, die nicht liefen, und Reparaturen, die nicht stattfanden. Der Schutz fuer die Einstellungen eines Mods traf auf die JAR-Datei des Mods selbst, weshalb die am haeufigsten installierten Mods nie aktualisiert, beim Versionswechsel eines Packs nie entfernt und bei beschaedigtem Archiv nie repariert wurden. Ein Pack mit modernem Loader waehlte die zu lesende Versionsdatei nach der Reihenfolge des Verzeichnisses. Der Launcher-Hash liess sich von einer einzigen Fehlerseite vergiften und blieb es ueber Neustarts hinweg. Der JVM-Argument-Baukasten konnte einen Satz erzeugen, mit dem die JVM nicht startet. Das Beenden gab nach einem Signal auf, das ein haengendes Spiel ignoriert.

### Highlights
- **Geschuetzte Mods werden wieder aktualisiert.** JEI, JourneyMap, VoxelMap und die Xaero-Karten galten als unantastbare Konfiguration, sodass ein Pack-Update sie nie ersetzte und eine beschaedigte JAR nie repariert wurde. Sie sind wieder gewoehnlicher Pack-Inhalt; die Konfigurationsverzeichnisse, fuer die der Schutz gedacht war, bleiben geschuetzt.
- **Packs mit modernen Loadern starten zuverlaessig.** Forge und NeoForge waehlten eine von zwei Versionsdateien nach der Reihenfolge des Dateisystems, und die falsche Wahl liess den Start mit einer Meldung ueber eine nie angeforderte Bibliothek scheitern.
- **Der JVM-Argument-Baukasten erzeugt einen startbaren Satz.** Ein Wechsel des Garbage Collectors und zweimal Anwenden konnte Argumente erzeugen, die die JVM rundweg ablehnt; sichtbar wurde davon nur ein Exit-Code.
- **Beenden beendet.** Bisher ging ein hoefliches Signal hinaus und mehr nicht; ein Spiel, das es ignoriert, wird nun samt seiner Kindprozesse hart beendet.
- **Nichts geht verloren.** Ein abgebrochener Schreibvorgang konnte die gesamte Skin-Bibliothek loeschen, und ein kyrillisch benanntes Preset ueberschrieb das zuvor gespeicherte.

## [2.4.0-beta4] - 2026-08-03

2.4.0-beta4 schließt die Wege, auf denen Code in einen Start gelangen konnte, den das Pack nie benannt hat. Ein installiertes Pack wird an seinen eigenen Bytes gemessen statt an seinen Dateinamen, die Prüfung wird unmittelbar vor dem Start noch einmal gestellt, und der Launcher trägt nichts mehr in den Prozess, was ihm nebenbei zugereicht wurde: seine Umgebung, die Argumente aus den Pack-Einstellungen, die nativen Bibliotheken in der Instanz und den Interpreter, den er ausführen soll. Die SmartyCraft-Serverliste gilt ab diesem Release als veraltet.

### Highlights
- **Ein Pack startet als das Pack, nach Inhalt.** Installierte Mods werden gegen die Bytes geprüft, die das Pack deklariert hat, nicht gegen ihre Dateinamen; eine unter einem bekannten Namen ausgetauschte Datei kommt nicht mehr durch.
- **Die Prüfung läuft beim Start erneut.** Sie lief bisher vor der Anmeldung, Minuten bevor der Prozess überhaupt existierte; ein dazwischen verändertes Pack wird jetzt bemerkt.
- **Nichts fährt nebenbei mit.** Einstellungen, die auf fremden Code zeigen, aus der Desktop-Sitzung geerbte Variablen und eine Laufzeitumgebung, die kein echtes Programm ist, werden bei einem Start mit Serveranmeldung abgewiesen.
- **Ein verändertes Pack sagt es.** Der Start bricht mit einer Meldung ab, statt ein Spiel zu starten, das dem Server ohnehin nicht beitreten kann.
- **Die SmartyCraft-Serverliste läuft aus.** In diesem Release gilt sie als veraltet, in 2.5.0 verschwindet sie -- an ihre Stelle treten Packs.

## [2.4.0-beta3] - 2026-08-02

2.4.0-beta3 macht Konten mit Zwei-Faktor-Schutz spielbar und schließt die Wege, auf denen ein Mod ungebeten in ein Pack gelangt. Die Anmeldung mit einem Code funktioniert durchgehend: Der Code wird einmal beim Druck auf Spielen abgefragt, und das Spiel startet auf einer Sitzung, die für genau diesen Start ausgestellt wurde. Auf der Inhaltsseite wird ein Pack vor jedem Start an seine eigene Dateiliste gehalten, Dateien, die sich nicht löschen lassen, gelten als das Hindernis, das sie sind, und der Durchgang berührt nur, was ein Loader tatsächlich ausführt -- Caches, Konfigurationen und die Buchführung des Launchers bleiben unangetastet.

### Highlights
- **Konten mit Zwei-Faktor-Schutz können spielen.** Die Anmeldung per Code funktioniert und wird einmal pro Start abgefragt statt immer wieder. Jede Hintergrundanmeldung, die die eben bestätigte Sitzung unbemerkt entwertet hat, ist entfernt.
- **Ein Pack startet als das Pack.** Von Hand hinzugefügte JARs werden vor dem Start entfernt, und eine Datei, die sich nicht entfernen lässt, führt zu einem Start ohne Anmeldung, statt so zu tun, als wäre alles in Ordnung.
- **Nur Mods werden aufgeräumt.** Mod-Caches, Konfigurationen und Reste werden nicht mehr mitgelöscht -- zuvor konnte ein Start den Cache umgeschriebener JARs eines Loaders leeren und einen vollständigen Neuaufbau kosten.
- **Packs installieren wieder, wo eine Plattformbibliothek fehlt.** Ein Pack, dessen Loader eine nur für macOS gedachte Bibliothek auflistet, scheitert unter Windows und Linux nicht mehr an der Installation.

## [2.4.0-beta2] - 2026-08-02

2.4.0-beta2 schließt die Lücke zwischen dem, was ein Pack zu sein behauptet, und dem, was tatsächlich startet. Eine Instanz wird vor jedem Start an die Liste der Dateien gehalten, aus denen das Pack besteht, nicht nur beim Synchronisieren; eine zwischen zwei Synchronisationen von Hand hinzugefügte JAR fährt nicht mehr mit. Ein Start trägt außerdem nur eine Sitzung, die er sich verdient hat: offline, eine ungeprüfte Instanz und eine fehlgeschlagene Auffrischung starten das Spiel ohne Token. Daneben meldet eine fehlgeschlagene Auffrischung vor dem Start das endlich selbst, statt als "Failed to verify username" aus Minecraft heraus aufzutauchen, und die mitgelieferte Laufzeitumgebung meldet nicht mehr bei jedem Start einen nicht passenden Klassenarchiv-Stand.

### Highlights
- **Ein Pack startet als das Pack.** Dateien, die in `mods/` eines installierten Packs gelegt wurden, werden vor dem Start entfernt, und der Launcher benennt, was er entfernt hat. Es lädt nur, was das Pack selbst deklariert.
- **Der Token bleibt aus Starts heraus, die ihn nicht verdient haben.** Ein Offline-Start, eine Instanz, für die der Launcher nicht einstehen kann, und ein Start, der den Anmeldeserver nicht erreicht hat, starten das Spiel ohne Sitzungstoken.
- **Der Launcher sagt, wenn die Sitzung veraltet ist.** Statt dass das Spiel den Serverbeitritt mit "Failed to verify username" verweigert, sagt der Launcher vorab, dass er die Sitzung nicht auffrischen konnte und was dagegen hilft.
- **Ein ruhigerer, leichterer Start.** Die mitgelieferte Laufzeitumgebung druckt nicht mehr bei jedem Start Klassenarchiv-Fehler, und ein nach einem Update veraltetes Archiv deaktiviert die Klassenteilung nicht mehr stillschweigend.

## [2.4.0-beta] - 2026-07-30

2.4.0-beta setzt die Vorschau fort und baut neu, was der Launcher mit dem Netzwerk tut. Jede Datei, die er auf die Platte holt -- eine Laufzeitumgebung, ein JDK, ein Pack, eine Mod, ein Loader-Installer, sein eigenes Update -- läuft jetzt über eine einzige Transfer-Engine, die erneut versucht, dort weitermacht, wo die Verbindung abriss, und auf einen Mirror ausweicht; eine geprüfte Datei behält eine Blockkarte, sodass ein beschädigtes Pack über seine beschädigten Blöcke repariert statt neu geladen wird. Die Builds eines Packs bekommen einen eigenen Bildschirm mit Änderungen je Build, Wechsel und Rückrollen, und Pack-Updates melden sich selbst im Benachrichtigungszentrum. Nightly-Builds und ein Vorabversionen-Schalter ersetzen das Update-Manager-Fenster. Ein Härtungs-Durchgang hält Sitzungs-Tokens aus Logs und Diagnosepaketen heraus, begrenzt eine akzeptierte Zertifikatsausnahme auf den Host, für den sie erteilt wurde, und beschränkt jeden Pfad und jedes Archiv, das ein Server-Dokument wählen darf. ProGuard ist weg, und das Linux-AppImage sinkt auf ~74 MB.

### Highlights
- **Dies ist eine Beta**. Sie trägt alles seit der 2.4.0-Vorschau -- melde Kaputtes bitte weiterhin im Issue-Tracker.
- **Downloads, die eine schlechte Verbindung überstehen**. Jeder Download wiederholt jetzt und macht dort weiter, wo er stehenblieb, statt neu zu beginnen, große Dateien werden in parallel geholten Blöcken übertragen, und es gibt einen Wechsel auf einen anderen Mirror -- ein Abbruch mitten in einer 200-MB-Laufzeitumgebung oder einem 300-MB-Resourcepack kostet Sekunden, nicht den ganzen Transfer.
- **Ein Pack reparieren, statt es neu zu laden**. Die Pack-Einstellungen bekommen Prüfen und Reparieren: geprüft wird gegen den Build, an den das Pack gebunden ist, und geholt werden nur die beschädigten Teile der beschädigten Dateien. Deine eigenen Jars, deine abgeschalteten optionalen Mods und deine bearbeiteten Configs bleiben unangetastet.
- **Jeder Build eines Packs, mit seinen Änderungen**. Ein Versionsbildschirm listet die vorgehaltenen Builds des Mirrors und zeigt, was jeder hinzufügt, aktualisiert und entfernt -- so wechselst du auf einen bestimmten Build oder rollst zurück, mit der Änderung vor Augen.
- **Updates melden sich selbst**. Ein neuer Pack-Build löst eine Benachrichtigung aus, und die Bibliothekskarte trägt eine anklickbare Update-Pille, statt dass ein Update still oder gar nicht passiert.
- **SmartyCraft-Server auf modernem Minecraft**. Der Beitritt zum Server eines SC-gebundenen Packs auf einer modernen Version funktioniert, und die Skins anderer Spieler werden geladen, statt auf den Standard zurückzufallen.
- **Ein leichterer Launcher**. Das Linux-AppImage sinkt von ~95,6 MB auf ~74 MB, ein eigenes Hintergrundbild wird in der Größe deines Displays zwischengespeichert statt bei jedem Start in voller Auflösung dekodiert, und der Inhalte-Tab knackt nicht mehr bei jedem Öffnen alle Jars neu.
- **Dein Sitzungs-Token bleibt aus den Logs heraus**. Es erreicht `game.log` nicht mehr, der Absturzbericht und das Diagnosepaket werden schon beim Schreiben bereinigt, und die Anmeldedatei wird nur für dich lesbar angelegt.

## [2.4.0-preview] - 2026-07-14

2.4.0 öffnet den Launcher zur weiteren Mod-Welt. Ein neuer Browse-Tab sucht und installiert Modrinth-Modpacks, importiert eine `.mrpack`, ein CurseForge-Zip oder eine Instanz aus einem anderen Launcher und baut ein Pack von Grund auf; ein Kleiderschrank verwaltet Skins und Umhänge über einer überarbeiteten 3D-Figur; der Launcher kann dem Farbschema des Desktops folgen; und ein Boot-Screen plus ein Wiederherstellungsmodus tragen einen Start, der schiefgeht. Darunter zieht die gesamte Oberfläche auf ein einziges Designsystem um, die Start-Engine teilt sich in GUI-freie Module mit einer nativen CLI, und der Build wechselt auf Java 26.

### Highlights
- **Dies ist eine Vorschau**. 2.4.0 ist ein großes, schnelllebiges Release, das früh als Vorschau erscheint -- rechne mit rauen Kanten und melde bitte alles Kaputte im Issue-Tracker.
- **Modpacks entdecken und installieren**. Ein neuer Browse-Tab durchsucht Modrinths Modpacks, zeigt ihre Beschreibungen im Launcher und installiert eines mit einem Klick -- und du kannst eine `.mrpack` oder ein CurseForge-Zip importieren oder ein leeres Pack von Grund auf anlegen.
- **Ein Kleiderschrank für deine Skins**. Ein neuer Kleiderschrank hält deine Skins als kleine 3D-Figuren, wendet einen auf SmartyCraft an, wählt einen Umhang oder startet vom Standardsatz des Spiels -- der Look deiner Figur an einem Ort.
- **Der Launcher folgt deinem Desktop**. Er kann das Hell-/Dunkel-Schema deines Systems selbst übernehmen und sein Thema an die Helligkeit des Hintergrunds anpassen, mit einem neuen Erscheinungsbild-Studio für Hintergrund- und Aussehens-Regler.
- **Ein Boot-Screen und ein Wiederherstellungsmodus**. Ein schneller Boot-Screen erscheint beim Start; wenn etwas schiefgeht, halte Shift (oder übergib `--recovery`), um einen fehlerhaften Teil abzuschalten oder zurückzusetzen -- ohne Neuinstallation.
- **Eine konsistente Oberfläche**. Die ganze UI zog auf ein einziges Designsystem um -- Flächen, Schaltflächen, Menüs und Einstellungsbereiche teilen dieselben Formen, Abstände und Icons und bleiben mit oder ohne Hintergrundbild lesbar.
- **Packs zeigen, was sie tun**. Eine Pack-Karte trägt jetzt einen Live-Startzustand (Vorbereiten / Herunterladen / Läuft), und ein Teil-Import nennt die Mods, die noch einen manuellen Download brauchen, statt wie ein leeres Pack zu wirken.

## [2.3.4] - 2026-06-15

Das Anpassungs-Release, konsolidiert. 2.3.4 macht die gesamte Oberfläche bearbeitbar -- und bearbeitbare Flächen tragen jetzt ihre eigenen Einstellungen --, baut das Profil um einen lebendigen 3D-Render deines Skins neu auf, bringt einen Update-Manager mit Kanälen und Rückrollen, gibt Benachrichtigungen ein festes Zuhause in der App und auf dem Desktop, lehrt die Oberfläche, in schmale Fenster zu passen, und bündelt den eigenen Schriftsatz des Launchers. Darunter: SmartyCraft-Modpacks treten ihren Servern bei, ohne etwas von SmartyCraft mitzuliefern, ein installiertes Modpack startet offline neu, der Speicher bemisst sich selbst, und ein tiefer Robustheits-Durchgang verhindert, dass eine kaputte Datei oder ein verirrtes Widget den Launcher umwirft. Es fasst die Linie 2.3.4-beta .. beta5 und alles seither zusammen.

### Highlights
- **Mach den Launcher zu deinem**. Strg+E zum Bearbeiten. Ziehe, skaliere, restyle und platziere jedes Widget frei -- auf der Startseite, in der Bibliothek, den Seitenleisten und der App-Hülle selbst -- mit Glas-Hintergrund je Widget und Speichern / Laden / Export von Layout-Presets. Auch Flächen haben jetzt eigene Einstellungen -- der Auswahlstil der linken Leiste liegt in ihrem Editor-Panel, nicht in einem globalen Menü.
- **Dein Skin in 3D**. Das Profil führt mit einem lebendigen, drehbaren 3D-Render deines Skins, von Grund auf ohne Abhängigkeit gezeichnet, und die Anmeldung liegt im Profil und ist auch abgemeldet erreichbar.
- **Ein Update-Manager mit Kanälen**. Das "i" neben der Version öffnet einen Manager: Release / Beta / Alpha (dazu Dev / Git aus dem Quellcode), aktualisieren oder zurückrollen, eine Desktop-Verknüpfung installieren. Der Info-Bildschirm prüft zudem selbst alle paar Minuten.
- **Benachrichtigungen, die bleiben**. Ein platzierbares Nachrichtenverlauf-Widget gruppiert Wiederholungen, wird zum Verwerfen gewischt und per "Nicht stören" stummgeschaltet -- und neu schickt der Launcher eine echte Desktop-Benachrichtigung, wenn er in den Tray rutscht, damit das nicht wie ein Absturz wirkt.
- **Der Launcher passt in schmale Fenster**. Leisten klappen per Wisch ein, die Serverliste blättert in Pillen, und der Info-Bildschirm stapelt seine Spalten, statt sie abzuschneiden.
- **SmartyCraft-Modpacks treten ihren Servern bei**. Ein Modpack aus dem Mirror, das auf einen SmartyCraft-Server ausgerichtet ist, verbindet sich und tritt bei, die Skins anderer Spieler werden geladen -- ohne dass etwas von SmartyCraft mitgeliefert wird.
- **Offline-Neustart**. Ein installiertes Modpack startet ohne Netzwerk; ein warmer Neustart sendet keine einzige Anfrage.
- **Adaptiver Speicher**. Eine nicht festgepinnte Instanz bemisst ihren Heap aus deinem echten RAM und verfeinert ihn über ein paar Sitzungen; pinne einen Wert, um das abzuschalten.
- **Der eigene Schriftsatz des Launchers**. Google Sans Flex und JetBrains Mono sind in die App eingebettet, sodass die Oberfläche auf jeder Maschine gleich aussieht, statt die System-Schriften zu leihen.
- **Ein Launcher, der nicht umkippt**. Eine kaputte Welt- oder Server-Datei, ein Widget mit verschwundenem Typ oder eine abgestürzte Fläche legen den Launcher nicht mehr lahm.

## [2.3.4-beta5] - 2026-06-09

Ein Profil-und-Updates-Release. Das Profil ist um einen lebendigen
3D-Render deines Skins herum neu aufgebaut, die Anmeldung ist ins Profil
gewandert und auch abgemeldet erreichbar. Ein neuer Update-Manager bringt
Release-Kanäle, das Zurückrollen auf eine frühere Version, die
Installation einer Desktop-Verknüpfung und -- für Entwickler -- das Bauen
des Launchers aus dem Quellcode.

### Highlights
- **Dein Skin in 3D**. Der Konto-Tab im Profil führt jetzt mit einem lebendigen, drehbaren 3D-Render deines Skins, von Grund auf ohne zusätzliche Abhängigkeit gezeichnet.
- **Anmeldung aus dem Profil**. Das Login-Formular liegt im Profil und ist auch abgemeldet erreichbar; das beengte Login in der rechten Leiste ist weg.
- **Ein Update-Manager mit Kanälen**. Das "i" neben der Version öffnet einen Manager: Kanal wählen (Release / Beta / Alpha, dazu Dev / Git aus dem Quellcode), auf eine neuere Version aktualisieren oder zurückrollen, und eine Desktop-Verknüpfung installieren.
- **Update-Prüfung im Hintergrund**. Der Info-Bildschirm prüft selbst alle paar Minuten auf Updates und färbt die laufende Version nach ihrem Kanal.
- **Eine kaputte Welt- oder Server-Datei stürzt den Launcher nicht mehr ab**. Eine fehlerhafte NBT-Länge legte beim Scan den ganzen Launcher lahm.

## [2.3.4-beta4] - 2026-06-07

Das SmartyCraft-Modpack-Release. Ein Modpack, das auf einen
SmartyCraft-Server ausgerichtet ist, verbindet sich jetzt und tritt dem
Server bei (inklusive der Skins anderer Spieler), ohne dass irgendetwas
von SmartyCraft mitgeliefert wird. Dazu: Offline-Neustart eines
installierten Modpacks, abhängigkeitsbewusste optionale Mods, eine
Konsole, die unter einer Logflut nicht mehr einfriert, und Korrekturen
am adaptiven Speicher.

### Highlights
- **SmartyCraft-Modpacks treten ihren Servern bei**. Ein Modpack aus dem
  Mirror, das auf einen SmartyCraft-Server ausgerichtet ist, verbindet sich
  jetzt und tritt bei, und die Skins anderer Spieler werden geladen.
- **Offline-Neustart**. Ein bereits installiertes Modpack startet ohne
  Netzwerk; ein warmer Neustart sendet keine einzige Netzwerkanfrage.
- **Optionale Mods ziehen ihre Abhängigkeiten nach**. Das Aktivieren eines
  optionalen Mods aktiviert auch die benötigten Bibliotheken, und das
  Umschalten eines Mods innerhalb einer austauschbaren Gruppe (z. B. eines
  Rezept-Browsers) deaktiviert den anderen.
- **Eine Konsole, die mithält**. Eine Logflut beim Laden der Mods friert das
  Launcher-Fenster nicht mehr ein und bringt es nicht mehr zum Absturz.
- **Adaptiver Speicher erkennt den echten RAM**. Der installierte Build erkennt
  jetzt den RAM des Rechners korrekt, und die adaptive Dimensionierung
  funktioniert unter ZGC und Shenandoah.

### Hinzugefügt
- SmartyCraft-Beitritt über einen Agenten: Für ein Modpack, das an einen
  SmartyCraft-Server gebunden ist, hängt der Launcher einen abhängigkeitsfreien
  `-javaagent` an, der authlib beim Laden der Klassen auf SmartyCraft umleitet
  (die join-, session- und profile-Aufrufe sowie die Textur-Domain-Whitelist)
  und die unsignierten Skins anderer Spieler lädt. Zwei Schalter unter
  Einstellungen -> Smarty: "Netzwerk-Agent" (an) und der ältere
  authlib-Bibliothekstausch (aus, als Rückfalloption behalten).
- Offline-Warmstart: Versions-Metadaten und ein passender Asset-Index werden
  von der Festplatte wiederverwendet, sodass ein installiertes Modpack ohne
  Netzwerkanfragen neu startet; eine fehlende oder geänderte Datei wird
  trotzdem einzeln geladen.
- Abhängigkeitsbewusstes Umschalten optionaler Mods: Das Aktivieren eines Mods
  aktiviert die von ihm benötigten Bibliotheken; Mods derselben Rolle bleiben
  gegenseitig ausschließend.
- Periodisches Schreiben der Profiler-Metriken: Die Sitzungsmetriken werden
  alle 30 Sekunden geschrieben, sodass ein harter Exit (ein `Runtime.halt`
  durch einen Mod, ein nativer Absturz) nicht mehr die ganze Sitzung verliert.
- Changelogs auf Russisch und Deutsch (`CHANGELOG_RU.md`, `CHANGELOG_DE.md`),
  ab diesem Release.

### Geändert
- Die Spielkonsole läuft vollständig außerhalb des UI-Threads, von der Aufnahme
  bis zum Rendern, sodass eine Logflut das Fenster nicht mehr blockieren oder
  zum Absturz bringen kann.
- Die adaptive Heap-Dimensionierung erkennt das Live-Set unter ZGC und
  Shenandoah, nicht nur unter G1 / Parallel / CMS / Serial.
- Die Auth-Host-Umleitung eines Modpacks richtet sich jetzt nach der Herkunft
  des Modpacks: Nur SmartyCraft- und Mirror-Modpacks werden umgeleitet;
  Modrinth-, lokale und eigene Modpacks behalten die Standard-Hosts.
- Der SmartyCraft-Skin-Patch schreibt eine Zeile ins Log, wenn ein geändertes
  `getTextures` nicht mehr gepatcht werden kann, statt die Skins anderer
  Spieler still zu verlieren.

### Behoben
- Der adaptive Speicher liest den echten Host-RAM im installierten Build (zuvor
  fiel er auf der gepackten Laufzeit auf feste 16 GB zurück und dimensionierte
  den Heap falsch).
