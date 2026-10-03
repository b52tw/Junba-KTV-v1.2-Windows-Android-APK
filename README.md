# 峻爸 KTV 多文字軌核對器 v1.2a

獨立的「錄音 + 多文字軌 + KTV 同步核對」工具。此專案不修改原本的峻爸 AI Transcriber。

## v1.2a 介面原則

- **KTV 全文與時間軸優先**：主畫面保留最大閱讀空間。
- **文字軌管理預設收合**：需要加入、移除或對齊文字軌時才打開。
- **Gemini 工具預設收合**：順稿、多人講者/錄音核對、API Key、模型選擇都只在使用時出現。
- **AI 不阻塞 KTV**：Gemini 503/429 或其他暫時錯誤不會停止播放、刪除原稿或覆蓋原文字軌。
- **AI 結果永遠新增新文字軌**：可拿來比較，不取代原始逐字稿。

## Gemini 穩定化

Gemini 順稿只送文字；「聽錄音核對／多人講者」才會上傳錄音，並在執行前再次詢問。

當 Gemini 回傳 503、429、5xx 或連線暫時異常時，程式會：

1. 自動退避重試（2 / 5 / 10 秒）。
2. 首選模型仍無法使用時，自動切換備援模型。
3. 最終仍失敗時只顯示非阻斷狀態，KTV、時間軸、原稿仍可繼續使用。

API Key 預設遮蔽；Windows 與 Android 都改成「按住才顯示」。

## Windows

GitHub Actions 只產生 **Single EXE**，不再產生 Portable。

Artifact：

`Junba-KTV-MultiTrack-v1.2a-Single-EXE-Windows-x64`

主要功能：播放/暫停/停止、±5 秒、變速、KTV 全文反白、時間軸同步、SRT/VTT/TXT/JSON/CSV/DOCX/HTML 文字軌、多文字軌比對、專案儲存、離線 KTV HTML 輸出、Gemini 順稿與多人講者核對。

## Android

手機版採獨立響應式配置，不是把 Windows 畫面硬縮小：

- 主畫面：錄音、播放器、KTV 全文、時間軸。
- 「文字軌」按鈕：叫出文字軌管理視窗。
- 「AI 工具」按鈕：從需要時才開啟 Gemini 工具視窗。
- 小螢幕可橫向捲動播放器按鈕，不會把 KTV 文字擠出畫面。

Artifact：

`Junba-KTV-MultiTrack-v1.2a-Android-APK`

若沒有設定 Android release keystore，Workflow 會產出可安裝測試的 Debug APK；設定 Secrets 後會產生 Release APK。

## GitHub Workflow

請把專案根目錄完整上傳，Workflow 路徑必須是：

`.github/workflows/build-windows-android-v1.2a.yml`

執行成功後只會產出兩個主要 Artifact：Windows Single EXE 與 Android APK。

## 建議安全設定

若 API Key 曾出現在截圖、公開 Repo 或公開訊息中，請到 Google AI Studio 撤銷舊 Key 並建立新 Key。不要把 API Key 寫進原始碼或 GitHub Repository。


## v1.2a APK Hotfix
GitHub v1.2 的 Android 實際失敗點為 `MainActivity.kt:530`：API Key 的 `EditText.text` 被直接指派 `String`。v1.2a 已改為 `setText(...)`，並在 Workflow preflight 阻擋舊寫法。
