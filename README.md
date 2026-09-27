# FDM Volley — Saint-Cyr Volley Ball

Appli Android (100 % hors ligne) pour remplir, signer et envoyer la feuille de match FSGT 78.

- L'interface est dans `app/src/main/assets/` (page web embarquée).
- Chaque push sur `main` construit l'APK signé : il est publié sur la branche **`apk`** (`fdm-volley.apk`).
- La clé de signature (`app/fdm-release.keystore`) doit rester la même pour que les mises à jour s'installent par-dessus l'appli existante. Dépôt privé : ne pas le rendre public.
