# Bitácora

App Android personal que lee ejercicio, pasos, sueño, frecuencia cardíaca y peso desde **Health Connect** (donde Samsung Health publica sus datos), genera recomendaciones con reglas locales y sincroniza una copia con **Supabase**.

## Arquitectura

```
Samsung Health ──► Health Connect ──► App (Kotlin + Compose)
                                       ├─ motor de reglas (local, sin red)
                                       └─ WorkManager cada 12 h ──► Supabase (Postgres + RLS)
```

- **Solo lectura** en Health Connect.
- **Supabase**: cada tabla tiene `user_id default auth.uid()` y RLS por dueño; el cliente nunca envía su `user_id`. Escrituras idempotentes (`upsert` por ID de Health Connect o por día). `sync_runs` registra cada sincronización.
- **Sesión** cifrada con AES-GCM y clave del Android Keystore. Sin backups automáticos (`allowBackup=false`).
- **Recomendaciones**: `domain/RecommendationEngine.kt`, cubierto por tests en `app/src/test`.

## 1. Crear el proyecto en Supabase

1. En [supabase.com](https://supabase.com) creá un proyecto nuevo (región São Paulo es la más cercana).
2. **SQL Editor → New query**: pegá el contenido de `supabase/migrations/20261015000000_init.sql` y ejecutalo.
   Con la CLI: `supabase link --project-ref <ref>` y `supabase db push`.
3. **Authentication → Sign In / Providers → Email**: dejá Email habilitado y **desactivá "Allow new users to sign up"**. Es una app de un solo usuario: nadie más tiene que poder registrarse.
4. **Authentication → Users → Add user → Create new user**: tu email y una contraseña larga, con **Auto Confirm User** marcado.
5. **Project Settings → API**: copiá la **Project URL** y la **anon / publishable key**. La `service_role` / secret key **no** va en la app ni en GitHub.

### Migraciones posteriores

Cada archivo nuevo en `supabase/migrations/` se ejecuta una vez en el SQL Editor, en orden:

- `20261016000000_strength_logs.sql`: sesiones de fuerza marcadas en la pestaña Rutina.
- `20261020000000_active_minutes_and_routines.sql`: minutos activos por día y rutinas de core y movilidad.

Los recorridos GPS **no** se sincronizan: quedan solo en el teléfono porque revelan domicilio y lugar de trabajo.

## 2. Clave de firma (una sola vez)

Sin una clave fija, cada build sale firmado distinto y Android no deja actualizar la app instalada.

```bash
keytool -genkeypair -v -keystore bitacora.jks -alias bitacora \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 bitacora.jks > bitacora.jks.b64   # en macOS: base64 -i bitacora.jks
```

Guardá `bitacora.jks` y sus contraseñas fuera del repo (gestor de contraseñas). Si la perdés, vas a tener que desinstalar y reinstalar.

## 3. Secrets en GitHub

**Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Valor |
| --- | --- |
| `SUPABASE_URL` | `https://<ref>.supabase.co` |
| `SUPABASE_ANON_KEY` | anon / publishable key |
| `SIGNING_KEYSTORE_BASE64` | contenido de `bitacora.jks.b64` |
| `SIGNING_STORE_PASSWORD` | contraseña del keystore |
| `SIGNING_KEY_ALIAS` | `bitacora` |
| `SIGNING_KEY_PASSWORD` | contraseña de la clave |

## 4. Compilar e instalar

- Cada push a `main` compila, corre los tests y, si el keystore está configurado, publica el APK en **Releases** como `v1.0.<número de build>`.
- También se puede publicar una versión con nombre propio: `git tag v1.1.0 && git push --tags`.
- En el celular: abrí el APK y permití "instalar apps desconocidas" para el navegador o el gestor de archivos.

## 5. Primer uso

1. En **Samsung Health → Ajustes → Health Connect**, activá ejercicio, pasos, sueño, frecuencia cardíaca y peso.
2. Abrí Bitácora → **Dar acceso**. Aceptá también **historial completo** y **acceso en segundo plano**.
3. **Ajustes → Iniciar sesión** con el usuario del paso 1.4 → **Sincronizar ahora**. La primera sincronización trae hasta 365 días.

## Consultas útiles

```sql
select * from weekly_exercise order by week_start desc limit 12;

select day, steps from daily_activity order by day desc limit 30;

select started_at, status, counts, error_code from sync_runs order by started_at desc limit 10;
```

## Desarrollo local

Android Studio, JDK 17. Para compilar con Supabase configurado, creá `local.properties` (ignorado por git):

```
SUPABASE_URL=https://<ref>.supabase.co
SUPABASE_ANON_KEY=...
```

Tests: `./gradlew :app:testDebugUnitTest`.

## Límites conocidos

- Health Connect entrega solo lo que Samsung Health publica: si una métrica no aparece, revisá los permisos en Samsung Health.
- La lectura en segundo plano depende de que el teléfono soporte esa función de Health Connect; si no, la app sincroniza al abrirla con **Sincronizar ahora**.
- Borrados hechos en Samsung Health no se propagan a Supabase.
