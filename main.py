import flet as ft
import httpx
import inspect
import json

SYSTEM_PROMPT = """Ты — Джарвис, персональный ИИ-помощник на Android в стиле дворецкого Тони Старка.
Отвечай вежливо, кратко и по делу, называя пользователя «сэр».
Если пользователь просит выполнить действие на телефоне, верни ТОЛЬКО JSON без лишнего текста:
{"action": "call", "contact": "Имя"}
{"action": "message", "app": "max", "contact": "Имя", "text": "текст"}
{"action": "history", "query": "запрос"}
{"action": "open_url", "url": "https://..."}
Если это обычный вопрос или диалог, отвечай обычным текстом."""

ALLOWED_ACTIONS = {"call", "message", "history", "open_url"}

def extract_json(text: str):
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    start = text.find("{")
    if start == -1:
        return None
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                try:
                    return json.loads(text[start : i + 1])
                except json.JSONDecodeError:
                    return None
    return None

def main(page: ft.Page):
    page.title = "J.A.R.V.I.S."
    page.theme_mode = ft.ThemeMode.DARK
    page.bgcolor = "#0B0F19"
    page.padding = 12

    api_key = page.client_storage.get("api_key") or ""
    model_name = page.client_storage.get("model_name") or "google/gemini-2.0-flash-exp:free"
    system_msg = {"role": "system", "content": SYSTEM_PROMPT}
    history_messages = [system_msg]

    chat_list = ft.ListView(expand=True, spacing=10, auto_scroll=True)

    def add_bubble(text: str, is_user: bool):
        bubble = ft.Row(
            [
                ft.Container(
                    content=ft.Text(text, color=ft.Colors.WHITE, size=15, selectable=True),
                    bgcolor="#1E3A8A" if is_user else "#1F2937",
                    padding=12,
                    border_radius=14,
                    width=280,
                )
            ],
            alignment=ft.MainAxisAlignment.END if is_user else ft.MainAxisAlignment.START,
        )
        chat_list.controls.append(bubble)
        page.update()
        return bubble

    def add_typing_indicator():
        indicator = ft.Row(
            [
                ft.Container(
                    content=ft.Row(
                        [
                            ft.ProgressRing(width=16, height=16, stroke_width=2, color="#3B82F6"),
                            ft.Text("Джарвис думает...", color="#9CA3AF", size=14),
                        ],
                        spacing=10,
                    ),
                    bgcolor="#1F2937",
                    padding=12,
                    border_radius=14,
                )
            ],
            alignment=ft.MainAxisAlignment.START,
        )
        chat_list.controls.append(indicator)
        page.update()
        return indicator

    async def trigger_macrodroid(action_data: dict) -> str:
        act = action_data.get("action")
        if act not in ALLOWED_ACTIONS:
            return "Сэр, получена неизвестная команда."

        if act == "open_url":
            url = action_data.get("url", "https://ya.ru")
            res = page.launch_url(url)
            if inspect.isawaitable(res):
                await res
            return f"Открываю {url}, сэр."

        clean_params = {k: v for k, v in action_data.items() if k != "action"}
        try:
            async with httpx.AsyncClient(timeout=6.0) as client:
                resp = await client.get(f"http://127.0.0{act}", params=clean_params)
                if resp.status_code == 200:
                    return f"Команда «{act}» выполнена через MacroDroid, сэр."
                return f"MacroDroid ответил кодом {resp.status_code}, сэр."
        except Exception as e:
            return f"Не удалось связаться с MacroDroid (порт 8765), сэр: {e}"

    def set_input_locked(locked: bool):
        input_field.disabled = locked
        send_button.disabled = locked
        page.update()

    async def ask_jarvis(user_text: str):
        nonlocal history_messages
        if not api_key:
            add_bubble("Сэр, сначала укажите ваш ключ OpenRouter в настройках (шестеренка сверху).", False)
            return

        history_messages.append({"role": "user", "content": user_text})
        if len(history_messages) > 25:
            history_messages = [system_msg] + history_messages[1:][-24:]

        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }
        payload = {
            "model": model_name,
            "messages": history_messages,
        }

        set_input_locked(True)
        typing = add_typing_indicator()
        reply = None

        try:
            async with httpx.AsyncClient(timeout=30.0) as client:
                resp = await client.post(
                    "https://openrouter.ai/api/v1/chat/completions",
                    headers=headers,
                    json=payload,
                )
                if resp.status_code != 200:
                    add_bubble(f"Сэр, ошибка сервера {resp.status_code}: {resp.text[:150]}", False)
                    return

                data = resp.json()
                first_choice = next(iter(data.get("choices", [])), {})
                reply = first_choice.get("message", {}).get("content", "").strip()
        except Exception as e:
            add_bubble(f"Ошибка связи с сервером, сэр: {e}", False)
            return
        finally:
            if typing in chat_list.controls:
                chat_list.controls.remove(typing)
            set_input_locked(False)

        if not reply:
            add_bubble("Сэр, модель вернула пустой ответ.", False)
            return

        cmd_data = extract_json(reply)
        if isinstance(cmd_data, dict) and "action" in cmd_data:
            status_text = await trigger_macrodroid(cmd_data)
            history_messages.append({"role": "assistant", "content": reply})
            add_bubble(status_text, False)
            return

        history_messages.append({"role": "assistant", "content": reply})
        add_bubble(reply, False)

    def on_send(e):
        text = input_field.value.strip()
        if not text or input_field.disabled:
            return
        input_field.value = ""
        add_bubble(text, True)
        page.run_task(ask_jarvis, text)

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
            page.client_storage.set("api_key", api_key)
            page.client_storage.set("model_name", model_name)
            page.close(dlg)
            add_bubble("Настройки сохранены, сэр. Я готов к работе.", False)

        dlg = ft.AlertDialog(
            title=ft.Text("Настройки Джарвиса"),
            content=ft.Column([key_input, model_input], tight=True),
            actions=[ft.TextButton("Сохранить", on_click=save_settings)],
        )
        page.open(dlg)

    input_field = ft.TextField(
        hint_text="Напишите Джарвису...",
        expand=True,
        border_radius=20,
        filled=True,
        fill_color="#1F2937",
        on_submit=on_send,
    )
    send_button = ft.IconButton(ft.Icons.SEND, icon_color="#3B82F6", on_click=on_send)

    page.appbar = ft.AppBar(
        title=ft.Text("J.A.R.V.I.S.", weight=ft.FontWeight.BOLD),
        bgcolor="#111827",
        actions=[ft.IconButton(ft.Icons.SETTINGS, on_click=open_settings)],
    )

    add_bubble("Системы запущены, сэр. Нажмите на шестеренку сверху, чтобы ввести ключ OpenRouter.", False)

    page.add(
        chat_list,
        ft.Row([input_field, send_button]),
    )

ft.app(target=main)
