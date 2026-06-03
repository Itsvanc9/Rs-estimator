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

## 🔜 Próximos (en orden)

### 1. Firebase Migration (PRIORITY — cimientos)
- Migrate localStorage → Firestore (RS_ESTIMATES, RS_CONTRACTS, RS_INVOICES, RS_CLIENTS, etc.)
- Photos → Firebase Storage
- Add firebase-firestore-compat.js + firebase-storage-compat.js CDN
- Offline sync: auto-sync when connection returns

### 2. WhatsApp Integration
- Button on estimate/contract to send summary via WhatsApp
- Pre-filled message: "Hola [Client], aquí está tu estimado por $[Total]. Responde ACEPTO para agendar."
- Uses whatsapp://send?text=... (no API needed, no cost)

### 3. Voice Dictation (Hands-Free Mode)
- Mic button in materials/labor sections
- Dictate: "Add 20 drywall sheets and 4 hours of painting"
- Auto-fills cost table cells
- Uses Web Speech API (free, needs internet) or AndroidBridge native speech

### 4. Photo Annotation
- Draw/annotate on photos with finger before adding to PDF
- Canvas overlay with arrows, circles, text
- Great for Fold 6 large screen
- Pure HTML5 Canvas, no dependencies

### 5. Offline Sync (depends on Firebase)
- Already offline with localStorage
- Real value: auto-sync when signal returns
- Requires Firebase migration first

### 6. Subcontractor Portal (future)
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
- Anotación/rayado de fotos con el dedo (ya en roadmap #4)
- Vista comparativa before/after lado a lado
