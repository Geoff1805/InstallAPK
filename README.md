<p align="center">
  <img src="app/src/main/ic_launcher-web.png" width="120" alt="InstallAPK Logo" />
</p>

# 📲 InstallAPK (GitHub Android Updater)

> **Application Android pour installer et mettre à jour automatiquement vos applications GitHub en 1 clic.**

Fini la corvée de copier manuellement les fichiers APK depuis votre PC vers votre smartphone ou de naviguer dans les artefacts d'actions. **InstallAPK** se connecte à vos dépôts GitHub et installe ou met à jour directement vos applications, y compris elle-même !

---

## ⚡ Fonctionnalités

- **Mise à jour en 1 clic (« Tout mettre à jour »)** : Parcourt automatiquement vos applications configurées (`Bullix`, `Needix`, `InstallAPK`), télécharge les derniers artefacts de build et lance l'installation.
- **Auto-Mise à Jour (Self-Updating)** : InstallAPK peut se mettre à jour elle-même dès qu'un nouveau build GitHub Actions est publié sur le repo `InstallAPK`.
- **Extraction transparente** : Décompresse les archives ZIP des artefacts GitHub Actions à la volée pour installer directement l'APK propre.
- **Détection des versions locales** : Interroge le `PackageManager` d'Android pour indiquer en temps réel la version installée et la comparer au dernier build disponible.
- **Sécurisé & Privé** : Supporte vos Personal Access Tokens (PAT) pour accéder à vos dépôts GitHub privés.

---

## 🚀 Projets pris en charge par défaut

1. **📚 Bullix** (`fr.bullix.app`)
2. **📦 Needix** (`com.aistudio.inventory.hmtst`)
3. **📲 InstallAPK** (`fr.installapk.app`)

---

## 🛠️ Architecture & Technologies

- **UI Moderne** : Jetpack Compose & Material Design 3.
- **Installation Native** : Android `FileProvider` (`ACTION_VIEW` / `application/vnd.android.package-archive`).
- **Réseau** : OkHttp 4 avec gestion du streaming de téléchargement et suivi de progression.
- **CI/CD First** : Compilation automatique du binaire APK sur GitHub Actions à chaque push.
