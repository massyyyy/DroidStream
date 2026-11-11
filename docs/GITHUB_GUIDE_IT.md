# Guida GitHub da zero (Windows 11)

1. github.com -> Sign up -> email, password, username -> verifica l'email.
2. In alto a destra "+" -> New repository -> nome DroidStream -> Public ->
   NON spuntare "Add a README file" -> Create repository.
3. Nella pagina del repo vuoto clicca il link "uploading an existing file".
4. Apri Desktop\DroidStream in Esplora file, premi Ctrl+A, trascina i file
   selezionati (il CONTENUTO, non la cartella) nell'area grigia di GitHub.
5. In basso clicca "Commit changes".
6. Tab "Actions": se appare "I understand my workflows, go ahead and enable them",
   cliccalo. Parte il workflow "build".
7. Apri il run e attendi (5-10 min). Verde = OK, rosso = errore.
8. In fondo alla pagina del run, sezione Artifacts: scarica "droidstream-m0" (ZIP).
9. Dentro lo ZIP: app-debug.apk e refs_report.md.
10. Installa sul telefono: USB + Debug USB attivo, poi in PowerShell:
    adb devices
    adb install -r "C:\percorso\app-debug.apk"
11. Se il build e' rosso: apri il job, apri lo step rosso, copia le ultime 40 righe.

Repo pubblico = minuti Actions gratuiti illimitati. Non caricare dati privati.
Alternativa se il drag and drop non funziona: installare GitHub Desktop, clonare il
repo, copiarci dentro i file, Commit + Push.