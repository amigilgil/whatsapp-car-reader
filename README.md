# 🚗 WhatsApp Car Reader

קורא הודעות וואטסאפ בזמן נהיגה — **בקול הספציפי של כל שולח**, מבוסס על הודעות קוליות שהוא שלח.

---

## ✨ איך זה עובד

```
הודעה מגיעה לוואטסאפ
         ↓
Android Accessibility Service קורא את ההודעה
         ↓
VoiceProfileManager בודק: יש קול משובט לשולח הזה?
    ├── כן → ElevenLabs TTS בקולו הספציפי
    └── לא → קול ברירת מחדל (Android TTS)
         ↓
הטלפון מחובר לרכב ב-Bluetooth A2DP
         ↓
"הודעה מדוד: האם אתה בדרך הביתה?"
```

### שיבוט קול אוטומטי
כשמישהו שולח **הודעה קולית** לוואטסאפ, האפליקציה:
1. שומרת את קובץ האודיו
2. צוברת דוגמאות (צריך לפחות 30 שניות)
3. מעלה ל-ElevenLabs ויוצרת קול סינתטי
4. מהרגע הזה — כל הודעת טקסט שלו נקראת בקולו!

---

## 🔧 דרישות

- Android 8.0 (API 26) ומעלה
- Android Studio Hedgehog+
- JDK 17
- מפתח API של [ElevenLabs](https://elevenlabs.io) (חינמי: 10,000 תווים/חודש)

---

## 🚀 התקנה

### 1. פתח ב-Android Studio
```bash
git clone <repo>
# פתח את התיקיה WhatsAppCarReader ב-Android Studio
```

### 2. הרץ על המכשיר
```
Build → Run (Shift+F10)
```

### 3. הגדרות ראשוניות באפליקציה

#### א. הפעל שירות נגישות
```
הגדרות אנדרואיד → נגישות → שירותים מותקנים → WhatsApp Car Reader → הפעל
```

#### ב. הפעל גישה להתראות (גיבוי)
```
הגדרות → אפליקציות → גישה מיוחדת → גישה להתראות → WhatsApp Car Reader
```

#### ג. הכנס מפתח ElevenLabs
1. הרשם ב-[elevenlabs.io](https://elevenlabs.io) (חינמי)
2. Profile → API Key → Copy
3. הדבק באפליקציה תחת הגדרות → ElevenLabs

#### ד. הגדר Bluetooth הרכב
```
הגדרות → Bluetooth הרכב → בחר את מכשיר הרכב שלך מהרשימה
```

---

## 📁 מבנה הפרויקט

```
app/src/main/java/com/whatsappcarreader/
├── service/
│   ├── WhatsAppAccessibilityService.kt   ← קורא הודעות מוואטסאפ
│   ├── WhatsAppNotificationListener.kt   ← גיבוי — קריאת התראות
│   └── CarReaderForegroundService.kt     ← שירות ראשי + ניהול תור
├── manager/
│   ├── VoiceProfileManager.kt            ← מנהל קולות לפי איש קשר
│   ├── ElevenLabsManager.kt              ← שיבוט קול + TTS
│   └── Database.kt                       ← Room DB (אנשי קשר + דוגמאות)
├── receiver/
│   ├── BluetoothReceiver.kt              ← מזהה חיבור/ניתוק מרכב
│   └── BootReceiver.kt                   ← הפעלה אוטומטית
├── ui/
│   ├── MainActivity.kt                   ← מסך ראשי
│   ├── SettingsActivity.kt               ← הגדרות
│   ├── ContactVoiceActivity.kt           ← פרטי קול לאיש קשר
│   └── ContactsAdapter.kt               ← רשימת אנשי קשר
├── model/
│   └── Models.kt                         ← כל ה-data classes
└── util/
    └── PrefsManager.kt                   ← SharedPreferences wrapper
```

---

## 🎙️ איכות שיבוט הקול

| כמות אודיו | איכות |
|-----------|-------|
| < 30 שניות | לא מספיק — משתמש בקול ברירת מחדל |
| 30–60 שניות | בסיסי — קול דומה |
| 1–2 דקות | טוב — ניכר בבירור |
| 2+ דקות | מצוין — כמעט זהה |

האפליקציה צוברת הודעות קוליות **אוטומטית** ברקע. ככל שהאדם שולח יותר הודעות קוליות — הקול ישתפר.

---

## ⚠️ הגבלות ידועות

- **iOS**: לא נתמך — Apple חוסמת גישה בין-אפליקציות
- **WhatsApp encryption**: האפליקציה קוראת מה שמוצג על המסך / בהתראות, לא מפענחת
- **גיבוי קולות**: הדוגמאות שמורות מקומית — גיבוי ידני מומלץ
- **ElevenLabs חינמי**: 10,000 תווים/חודש ≈ ~500 הודעות קצרות

---

## 🔒 פרטיות

- כל הנתונים שמורים **מקומית** על המכשיר
- הודעות **לא** נשלחות לשרת כלשהו
- רק קולות (לא תוכן הודעות) עולים ל-ElevenLabs לצורך שיבוט
- ניתן למחוק כל פרופיל קול מהאפליקציה

---

## 📝 רישיון

MIT — שימוש חופשי לצרכים אישיים.
