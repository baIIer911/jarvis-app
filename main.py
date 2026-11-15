import os
import json
import httpx
import flet as ft

SYSTEM_PROMPT = """Ты — Джарвис, персональный ИИ-ассистент на телефоне пользователя. Общайся кратко, вежливо, с лёгкой харизмой и называй пользователя «сэр».
Если пользователь просит выполнить действие на телефоне, ответь строго в формате JSON (без лишнего текста вокруг):
1. Позвонить: {"action": "call", "contact": "имя или номер", "reply": "Набираю контакт, сэр."}
2. Написать сообщение в MAX: {"action": "message", "contact": "имя", "text": "текст сообщения", "reply": "Отправляю сообщение через MAX, сэр."}
3. Найти в интернете / истории: {"action": "history", "query": "поисковый запрос", "reply": "Ищу информацию, сэр."}
4. Открыть сайт или YouTube: {"action": "url", "url": "https://...", "reply": "Открываю, сэр."}
Если это обычный разговор или вопрос — отвечай обычным текстом."""

def main(page: ft.Page):
    page.title = "J.A.R.V.I.S."
    page.theme_mode = ft.ThemeMode.DARK
    page.bgcolor = "#0A0E17"
    page.padding = 0

    cfg = json.load(open("settings.json", encoding="utf-8")) if os.path.exists("settings.json") else {}
    api_key = cfg.get("api_key", "")
    model_name = cfg.get("model_name", "google/gemini-2.0-flash-exp:free")
    history = [{"role": "system", "content": SYSTEM_PROMPT}]

    chat_list = ft.ListView(expand=True, spacing=12, padding=16, auto_scroll=True)
    status_text = ft.Text("Системы в норме • Ожидание команды", size=12, color="#38BDF8")

    def add_bubble(text: str, is_user: bool):
        chat_list.controls.append(
            ft.Row(
                [
                    ft.Container(
                        content=ft.Text(text, color="#F8FAFC", size=15, selectable=True),
                        bgcolor="#1E3A8A" if is_user else "#111827",
                        border=ft.border.all(1, "#3B82F6" if is_user else "#1F2937"),
                        border_radius=16,
                        padding=14,
                        width=280,
                    )
                ],
                alignment=ft.MainAxisAlignment.END if is_user else ft.MainAxisAlignment.START,
            )
        )
        page.update()

    async def trigger_macrodroid(endpoint: str, params: dict):
        try:
            async with httpx.AsyncClient(timeout=6.0) as client:
                await client.get(f"http://127.0.0{endpoint}", params=params)
        except Exception as ex:
            add_bubble(f"⚠ Связь с MacroDroid: {ex}", False)

    async def send_message(e):
        user_text = msg_input.value.strip()
        if not user_text:
            return
        if not api_key:
            add_bubble("Сэр, сначала укажите ключ OpenRouter в настройках (⚙️ вверху справа).", False)
            return

        msg_input.value = ""
        add_bubble(user_text, True)
        status_text.value = "Обработка запроса..."
        page.update()

        history.append({"role": "user", "content": user_text})

        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                resp = await client.post(
                    "https://openrouter.ai",
                    headers={
                        "Authorization": f"Bearer {api_key}",
                        "Content-Type": "application/json",
                    },
                    json={"model": model_name, "messages": history[-12:]},
                )
                data = resp.json()
                if "error" in data:
                    add_bubble(f"Ошибка OpenRouter: {data['error'].get('message', data['error'])}", False)
                    status_text.value = "Ошибка API"
                    page.update()
                    return

                raw_reply = data["choices"][0]["message"]["content"].strip()
                history.append({"role": "assistant", "content": raw_reply})

                clean_json = raw_reply.replace("```json", "").replace("```", "").strip()
                if clean_json.startswith("{") and clean_json.endswith("}"):
                    cmd = json.loads(clean_json)
                    action = cmd.get("action")
                    reply = cmd.get("reply", "Выполняю, сэр.")
                    add_bubble(reply, False)

                    if action == "call":
                        await trigger_macrodroid("call", {"contact": cmd.get("contact", "")})
                    elif action == "message":
                        await trigger_macrodroid(
                            "message",
                            {"contact": cmd.get("contact", ""), "text": cmd.get("text", "")},
                        )
                    elif action == "history":
                        await trigger_macrodroid("history", {"query": cmd.get("query", "")})
                    elif action == "url":
                        page.launch_url(cmd.get("url", "https://yandex.ru"))
                else:
                    add_bubble(raw_reply, False)

        except Exception as ex:
            add_bubble(f"Сбой связи с сервером, сэр: {ex}", False)

        status_text.value = "Системы в норме • Ожидание команды"
        page.update()

    def open_settings(e):
        key_input = ft.TextField(
            label="Ключ OpenRouter (sk-or-...)",
            value=api_key,
            password=True,
            can_reveal_password=True,
        )
        model_input = ft.TextField(label="Модель", value=model_name)

        def save_settings(ev):
            nonlocal api_key, model_name
            api_key = key_input.value.strip()
            model_name = model_input.value.strip()
            with open("settings.json", "w", encoding="utf-8") as f:
                json.dump({"api_key": api_key, "model_name": model_name}, f)
            page.pop_dialog()
            add_bubble("Настройки сохранены, сэр. Я готов к работе.", False)

        dlg = ft.AlertDialog(
            title=ft.Text("Настройки Джарвиса"),
            content=ft.Column([key_input, model_input], tight=True),
            actions=[ft.TextButton("Сохранить", on_click=save_settings)],
        )
        page.show_dialog(dlg)

    msg_input = ft.TextField(
        hint_text="Введите команду или вопрос...",
        expand=True,
        border_radius=20,
        bgcolor="#111827",
        border_color="#1E3A8A",
        color="#F8FAFC",
        on_submit=send_message,
    )

    header = ft.Container(
        content=ft.Row(
            [
                ft.Column(
                    [
                        ft.Text("J.A.R.V.I.S.", size=20, weight=ft.FontWeight.BOLD, color="#38BDF8"),
                        status_text,
                    ],
                    spacing=2,
                ),
                ft.IconButton(icon=ft.Icons.SETTINGS, icon_color="#38BDF8", on_click=open_settings),
            ],
            alignment=ft.MainAxisAlignment.SPACE_BETWEEN,
        ),
        padding=ft.padding.only(left=16, right=12, top=42, bottom=12),
        bgcolor="#0F172A",
        border=ft.border.only(bottom=ft.BorderSide(1, "#1E293B")),
    )

    input_bar = ft.Container(
        content=ft.Row(
            [
                msg_input,
                ft.IconButton(
                    icon=ft.Icons.SEND_ROUNDED,
                    icon_color="#0A0E17",
                    bgcolor="#38BDF8",
                    on_click=send_message,
                ),
            ],
            spacing=8,
        ),
        padding=ft.padding.only(left=12, right=12, top=8, bottom=16),
        bgcolor="#0F172A",
    )

    page.add(ft.Column([header, chat_list, input_bar], expand=True, spacing=0))
    add_bubble("Добро пожаловать, сэр. Все системы Джарвиса готовы к работе.", False)

ft.run(main)
