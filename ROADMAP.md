# RS Estimator — Roadmap

## ✅ Completado
- Biometric lock: two options (fingerprint OR password)
- All hardcoded Spanish text → English default with i18n
- Phantom contract after reinstall → purge on startup
- Share modal phantom entries fix
- Print/PDF action sheet redesign + Cancel button
- Notification bell moved to top-right corner
- Cursor bug in Edit Payment Milestone inputs
- Invoice navigation after save → goes to detail
- Margin precision `.toFixed(1)` in expenses
- Invoice PDF redesigned to match estimate/contract branding
- Invoice list grouped by client and project
- Mark contract complete → removes from calendar
- Firestore cloud sync (Phase 1): localStorage primary + Firestore backup/cross-device,
  offline persistence enabled, merge-by-id preserving local photos. Verified working in
  Firebase Console (users/{uid}/sync/RS_CONTRACTS, RS_CLIENTS, RS_ESTIMATES populated).
- WhatsApp direct-to-client messaging: "📲 Send to Client" button on saved estimates and
  invoice detail (when clientPhone exists). Attaches the actual PDF (estimate/invoice) plus
  a pre-filled friendly message with "Responde ACEPTO" call-to-action, opens WhatsApp
  contact picker. i18n (EN/ES) templates added.
- Voice Dictation: "🎤 Dictate" button in Materials card. Uses native Android speech
  recognizer (RecognizerIntent, with Web Speech API fallback for browser). Parses phrases
  like "20 drywall sheets at 15 dollars" or "4 hours of painting with 2 workers at 25 per
  hour" (EN/ES) to auto-fill materials table and labor fields.

## 🔜 Próximos (en orden)

### 1. Firebase Migration — Phase 2 (Storage para fotos)
- Photos (receipts, milestones, signatures, logo) → Firebase Storage
- Add firebase-storage-compat.js CDN
- Replace base64 photo fields with Storage URLs, sync via Firestore

### 2. Photo Annotation
- Draw/annotate on photos with finger before adding to PDF
- Canvas overlay with arrows, circles, text
- Great for Fold 6 large screen
- Pure HTML5 Canvas, no dependencies

### 3. Offline Sync (depends on Firebase)
- Already offline with localStorage
- Real value: auto-sync when signal returns
- Requires Firebase migration first

### 4. Subcontractor Portal (future)
- Unique link for subcontractors
- They see: address, job description, photo upload button
- They do NOT see: prices, margins, client billing
- Requires Firebase Hosting + Firestore

## 💡 Ideas Adicionales (de auditoría)

### Finanzas
- Pagos parciales en invoices (registrar abonos, no solo "pagado/no pagado")
- Recordatorios automáticos de cobro cuando invoice lleva N días vencida
- Recibo de pago formal al marcar invoice como pagada
- Proyección de flujo de caja: "vas a cobrar $X los próximos 30 días"
- Descuentos por línea en estimados

### Clientes
- Historial del cliente: total gastado, # proyectos, fecha último trabajo
- Seguimiento de leads: llamé, mandé estimado, esperando respuesta
- Balance pendiente total del cliente (suma de invoices abiertas)

### Reportes
- P&L mensual por cliente / tipo de trabajo / equipo
- Resumen de gastos de toda la empresa (no solo por contrato)
- Métricas de cobro: quién tarda más en pagar, promedio de días

### Contratos
- Contratos T&M (tiempo y materiales) además de precio fijo
- Timesheet: horas reales trabajadas vs. horas estimadas

### Fotos
- Anotación/rayado de fotos con el dedo (ya en roadmap #3)
- Vista comparativa before/after lado a lado
