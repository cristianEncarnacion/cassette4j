# Cómo publicar cassette4j en Maven Central

La publicación es automática: cuando se publica un Release de GitHub con tag `vX.Y.Z`, GitHub Actions compila, firma y publica.
Solo hay que preparar la cuenta y 4 secretos una vez.

## 1. Crear la cuenta en Central (5 minutos)

1. Entra a https://central.sonatype.com y dale a **Sign In → Continue with GitHub** con tu cuenta `cristianEncarnacion`.
2. Ve a **Publish → Namespaces**. Debe aparecer `io.github.cristianencarnacion` ya verificado.
   - Si aparece con mayúsculas (`io.github.cristianEncarnacion`), avísale a Claude para cambiar el `groupId`.
3. Ve a tu perfil → **View Account → Generate User Token**. Copia el *username* y el *password* que te muestra (solo se ven una vez).

## 2. Crear la llave GPG (Windows, 5 minutos)

1. Instala Gpg4win: https://gpg4win.org (o `winget install GnuPG.Gpg4win`).
2. Abre PowerShell y crea la llave (pon tu nombre, tu correo y una contraseña que recuerdes):

   ```powershell
   gpg --full-generate-key
   ```

   Elige: `ECC (sign and encrypt)` → `Curve 25519` → expiración `2y`.
3. Mira el ID de la llave (la cadena larga después de `ed25519/` o en la línea de `sec`):

   ```powershell
   gpg --list-secret-keys --keyid-format=long
   ```

4. Publícala para que Central pueda verificar las firmas:

   ```powershell
   gpg --keyserver keyserver.ubuntu.com --send-keys TU_KEY_ID
   ```

5. Exporta la llave privada a un archivo para el paso 3 (no lo compartas con nadie y bórralo al terminar):

   ```powershell
   gpg --armor --export-secret-keys TU_KEY_ID > cassette4j-gpg.asc
   ```

## 3. Guardar 4 secretos en GitHub

Abre https://github.com/cristianEncarnacion/cassette4j/settings/secrets/actions y crea con **New repository secret**:

| Nombre | Valor |
|---|---|
| `CENTRAL_USERNAME` | el *username* del token de Central |
| `CENTRAL_PASSWORD` | el *password* del token de Central |
| `GPG_PRIVATE_KEY` | todo el contenido de `cassette4j-gpg.asc` (desde `-----BEGIN` hasta `-----END ...-----`) |
| `GPG_PASSPHRASE` | la contraseña que pusiste a la llave |

Después borra `cassette4j-gpg.asc`.

## 4. Publicar

En GitHub ve a **Releases → Draft a new release**, crea el tag `v0.1.0` sobre `main` y dale a **Publish release**.
El workflow **Release to Maven Central** publica esa versión. En unos 30 minutos aparece en
https://central.sonatype.com/artifact/io.github.cristianencarnacion/cassette4j-junit5

## Nuevas versiones

Cambia la versión en los 4 `pom.xml` y en `examples/quarkus-langchain4j/pom.xml`
(`mvn versions:set -DnewVersion=0.2.0`), haz commit y publica un Release con el tag `v0.2.0`.
