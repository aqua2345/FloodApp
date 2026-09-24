import os
import glob
import faiss
import numpy as np
import datetime
import json
import re
import math
import time
from openai import OpenAI
from sentence_transformers import SentenceTransformer
from flood_parser import get_flood_info_if_needed, get_multiple_sources, DISTRICT_MAP
import urllib.request
import urllib.parse


class FloodAssistant:
    def __init__(self):
        self.api_key = "pza_KIBON6kNFdyYPXSepyU-Ucz_swnRyAOv"
        self.client = OpenAI(
            base_url="https://polza.ai/api/v1",
            api_key=self.api_key,
        )
        self.model_name = "qwen/qwen3-30b-a3b"

        print("🔎 Загрузка модуля поиска...")
        self.encoder = SentenceTransformer('paraphrase-multilingual-MiniLM-L12-v2')
        self.histories = {}
        self.documents = []
        self.index = None

        self.twogis_geocoder_key = os.environ.get("TWOGIS_GEOCODER_KEY", "9d5beae6-63c6-446c-b8f4-4a63424d892d")

        # Кеш на диск — адрес геокодируется один раз за всё время работы сервера
        self._geocode_cache_file = "data/geocode_cache.json"
        self._pvr_geocode_cache = self._load_geocode_cache()

        if not os.path.exists("logs"):
            os.makedirs("logs")

        self.load_knowledge_base()
        print(f"🚀 Система готова. Отладка включена.")

    def load_knowledge_base(self):
        self.documents = []
        self.doc_sources = []

        if not os.path.exists("data"):
            os.makedirs("data")

        files = glob.glob("data/*.txt")
        for file_path in files:
            with open(file_path, "r", encoding="utf-8") as f:
                content = f.read()
                blocks = [b.strip() for b in content.split('\n\n') if len(b.strip()) > 10]
                if not blocks:
                    blocks = [content.strip()]

                self.documents.extend(blocks)
                self.doc_sources.extend([file_path] * len(blocks))

        if self.documents:
            embeddings = self.encoder.encode(self.documents)
            self.index = faiss.IndexFlatL2(embeddings.shape[1])
            self.index.add(np.array(embeddings).astype('float32'))

    def get_district_from_coords(self, lat, lon):
        """Обратный геокодинг через 2GIS API — координаты → район."""
        try:
            url = (
                "https://catalog.api.2gis.com/3.0/items/geocode"
                f"?lat={lat}&lon={lon}"
                "&fields=items.point"
                f"&key={self.twogis_geocoder_key}"
            )
            print(f"🌐 [GEO] Запрос к 2GIS Геокодеру: lat={lat:.4f}, lon={lon:.4f}")
            req = urllib.request.Request(url, headers={"User-Agent": "FloodAssistant72/1.0"})
            with urllib.request.urlopen(req, timeout=8) as resp:
                data = json.loads(resp.read().decode('utf-8'))

            items = data.get("result", {}).get("items", [])
            if not items:
                print(f"⚠️  [GEO] 2GIS: объект не найден по координатам ({lat}, {lon})")
                return None

            full_name = items[0].get("full_name", "")
            address_name = items[0].get("address_name", "")
            address = (full_name + " " + address_name).lower()
            print(f"🏠 [GEO] 2GIS ответ: {full_name}")

            # Ищем совпадение с известными районами
            for district_key in DISTRICT_MAP.keys():
                if district_key in address:
                    print(f"✅ [GEO] Район определён: {district_key}")
                    return district_key.capitalize()

            # Локация есть, но не в DISTRICT_MAP (например, г. Тюмень)
            raw_location = full_name.strip()
            if raw_location:
                print(f"⚠️  [GEO] Локация не в DISTRICT_MAP, используем сырое название: '{raw_location}'")
                return raw_location
            return None

        except Exception as e:
            print(f"❌ [GEO] Ошибка геокодирования 2GIS ({lat}, {lon}): {e}")
            return None

    def agent_search(self, query):
        if not self.index:
            return ""

        query_vector = self.encoder.encode([query])

        # Ищем топ-6 похожих чанков (вместо 2)
        k = min(6, len(self.documents))
        _, indices = self.index.search(np.array(query_vector).astype('float32'), k)

        # Определяем файлы, из которых пришли найденные чанки
        matched_sources = set()
        for idx in indices[0]:
            if idx < len(self.documents):
                matched_sources.add(self.doc_sources[idx])

        # Возвращаем ВСЕ чанки из всех попавших файлов
        result_blocks = []
        for i, doc in enumerate(self.documents):
            if self.doc_sources[i] in matched_sources:
                result_blocks.append(doc)

        return "\n\n".join(result_blocks)

    # ─────────────────────────────────────────────────────────────
    # ПАРСИНГ ПВР ИЗ TXT-ФАЙЛОВ БАЗЫ ЗНАНИЙ
    # ─────────────────────────────────────────────────────────────
    def load_pvr_from_knowledge_base(self) -> list:
        pvr_list = []
        pvr_files = glob.glob("data/ПВР_*.txt")

        if not pvr_files:
            print("⚠️  [PVR] Файлы ПВР_*.txt не найдены в data/")
            return pvr_list

        for file_path in pvr_files:
            district_name = os.path.basename(file_path).replace("ПВР_", "").replace(".txt", "")
            try:
                with open(file_path, "r", encoding="utf-8") as f:
                    content = f.read()

                blocks = re.split(r'\n(?=.*?https?://)', content)

                for block in blocks:
                    block = block.strip()
                    if not block or 'Адрес ПВР' not in block:
                        continue

                    pvr = {
                        "name": "",
                        "address": "",
                        "district": district_name,
                        "phone": "",
                        "capacity": 0,
                    }

                    lines = block.split('\n')

                    # Первая непустая строка до http:// — название ПВР
                    for line in lines:
                        line = line.strip()
                        if not line or line.startswith('ПУНКТЫ'):
                            continue
                        clean = re.sub(r'\s*https?://\S+', '', line).strip()
                        if clean:
                            pvr["name"] = clean
                            break

                    for line in lines:
                        line = line.strip()
                        if 'Наименование ПВР' in line:
                            candidate = (line.split('\t')[-1].strip()
                                         if '\t' in line
                                         else re.sub(r'Наименование ПВР\s*', '', line).strip())
                            if candidate:
                                pvr["name"] = candidate
                        elif 'Адрес ПВР' in line:
                            pvr["address"] = (line.split('\t')[-1].strip()
                                              if '\t' in line
                                              else re.sub(r'Адрес ПВР\s*', '', line).strip())
                        elif 'Телефон ПВР' in line:
                            pvr["phone"] = (line.split('\t')[-1].strip()
                                            if '\t' in line
                                            else re.sub(r'Телефон ПВР\s*', '', line).strip())
                        elif 'Вместимость' in line:
                            cap_match = re.search(r'\d+', line)
                            if cap_match:
                                pvr["capacity"] = int(cap_match.group())

                    if pvr["name"] and pvr["address"]:
                        pvr_list.append(pvr)

            except Exception as e:
                print(f"❌ [PVR] Ошибка при чтении {file_path}: {e}")

        print(f"✅ [PVR] Загружено {len(pvr_list)} ПВР из {len(pvr_files)} файлов")
        return pvr_list

    def warmup_geocoding(self):
        """
        Геокодирует все ПВР при первом запросе маршрута.
        Результаты сохраняются в data/geocode_cache.json —
        при следующих запросах всё берётся из кеша мгновенно.
        """
        if getattr(self, '_geocoding_warmed_up', False):
            return  # уже прогрето

        all_pvr = self.load_pvr_from_knowledge_base()
        uncached = [p for p in all_pvr
                    if p["address"].strip() not in self._pvr_geocode_cache]

        if not uncached:
            print("📦 [GEOCODE] Все ПВР уже в кеше, прогрев не нужен")
            self._geocoding_warmed_up = True
            return

        print(f"🔥 [GEOCODE] Прогрев: геокодирую {len(uncached)} новых ПВР "
              f"(из {len(all_pvr)} всего)...")
        ok = 0
        for pvr in uncached:
            lat, lon = self.geocode_address(pvr["address"], pvr["district"])
            if lat is not None:
                ok += 1

        print(f"🔥 [GEOCODE] Прогрев завершён: {ok}/{len(uncached)} успешно, "
              f"кеш сохранён в {self._geocode_cache_file}")
        self._geocoding_warmed_up = False  # сбрасываем чтобы подхватить новые файлы при рестарте

    def _load_geocode_cache(self) -> dict:
        """Загружает кеш геокодирования с диска."""
        if os.path.exists(self._geocode_cache_file):
            try:
                with open(self._geocode_cache_file, 'r', encoding='utf-8') as f:
                    cache = json.load(f)
                print(f"📦 [GEOCODE] Загружен кеш: {len(cache)} адресов")
                return cache
            except Exception as e:
                print(f"⚠️  [GEOCODE] Ошибка чтения кеша: {e}")
        return {}

    def _save_geocode_cache(self):
        """Сохраняет кеш геокодирования на диск."""
        try:
            os.makedirs(os.path.dirname(self._geocode_cache_file), exist_ok=True)
            with open(self._geocode_cache_file, 'w', encoding='utf-8') as f:
                json.dump(self._pvr_geocode_cache, f, ensure_ascii=False, indent=2)
        except Exception as e:
            print(f"⚠️  [GEOCODE] Ошибка сохранения кеша: {e}")

    def geocode_address(self, address: str, district: str) -> tuple:
        """
        Геокодирует адрес ПВР.
        Порядок провайдеров:
          1. 2GIS (бесплатно, без ключа, отлично знает РФ)
          2. Яндекс Геокодер (если задан ключ и 2GIS не нашёл)
        Результат кешируется на диск — каждый адрес запрашивается только один раз.
        """
        cache_key = address.strip()
        if cache_key in self._pvr_geocode_cache:
            cached = self._pvr_geocode_cache[cache_key]
            if cached is not None:
                return (cached[0], cached[1])
            return (None, None)

        query = address.strip()
        if 'тюменск' not in query.lower() and 'россия' not in query.lower():
            query = f"{query}, Тюменская область, Россия"

        # ── Провайдер 1: 2GIS ───────────────────────────────────────────────
        result = self._geocode_2gis(query)

        # ── Провайдер 2: Яндекс (fallback) ──────────────────────────────────
        if result == (None, None) and self.twogis_geocoder_key:
            result = self._geocode_yandex(query)

        if result != (None, None):
            self._pvr_geocode_cache[cache_key] = list(result)
        else:
            self._pvr_geocode_cache[cache_key] = None
            print(f"   ❌ Не удалось геокодировать: {address}")

        self._save_geocode_cache()
        return result

    def _geocode_2gis(self, query: str) -> tuple:
        """
        2GIS Public Geocoder — не требует ключа, отлично работает с адресами РФ.
        Использует публичный API catalog.api.2gis.com (тот же что в приложении 2ГИС).
        """
        try:
            url = (
                "https://catalog.api.2gis.com/3.0/items/geocode"
                f"?q={urllib.parse.quote(query)}"
                "&fields=items.point"
                f"&key={self.twogis_geocoder_key}"
            )
            print(f"🔍 [GEOCODE] 2GIS: '{query}'")
            time.sleep(0.3)
            req = urllib.request.Request(url, headers={"User-Agent": "FloodAssistant72/1.0"})
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode('utf-8'))

            items = data.get("result", {}).get("items", [])
            if not items or "point" not in items[0]:
                print(f"   2GIS: ничего не найдено")
                return (None, None)

            point = items[0]["point"]
            lat, lon = float(point["lat"]), float(point["lon"])
            name = items[0].get("full_name", items[0].get("name", ""))
            print(f"   ✅ 2GIS: {name[:60]} → ({lat:.5f}, {lon:.5f})")
            return (lat, lon)

        except Exception as e:
            print(f"   2GIS ERR: {e}")
            return (None, None)

    def _geocode_yandex(self, query: str) -> tuple:
        """Яндекс Геокодер HTTP API — fallback если 2GIS не нашёл."""
        try:
            url = (
                "https://geocode-maps.yandex.ru/1.x/"
                f"?apikey={self.twogis_geocoder_key}"
                f"&geocode={urllib.parse.quote(query)}"
                "&format=json&results=1&lang=ru_RU"
            )
            print(f"🔍 [GEOCODE] Яндекс fallback: '{query}'")
            time.sleep(0.2)
            req = urllib.request.Request(url, headers={"User-Agent": "FloodAssistant72/1.0"})
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode('utf-8'))

            members = (data.get("response", {})
                          .get("GeoObjectCollection", {})
                          .get("featureMember", []))
            if not members:
                return (None, None)

            geo = members[0]["GeoObject"]
            pos = geo["Point"]["pos"].split()   # Яндекс: "lon lat"
            lon, lat = float(pos[0]), float(pos[1])
            print(f"   ✅ Яндекс: {geo.get('name','')} → ({lat:.5f}, {lon:.5f})")
            return (lat, lon)

        except urllib.error.HTTPError as e:
            print(f"   Яндекс HTTP {e.code}: {'неверный ключ' if e.code==403 else e}")
        except Exception as e:
            print(f"   Яндекс ERR: {e}")
        return (None, None)

    def haversine_distance(self, lat1, lon1, lat2, lon2) -> float:
        R = 6371.0
        phi1, phi2 = math.radians(lat1), math.radians(lat2)
        dphi = math.radians(lat2 - lat1)
        dlambda = math.radians(lon2 - lon1)
        a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
        return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))

    def get_nearest_pvr(self, user_lat: float, user_lon: float, top_n: int = 3) -> list:
        print(f"\n🗺️  [ROUTE] Поиск ближайших ПВР к ({user_lat}, {user_lon})")

        # Прогрев кеша при первом запросе — геокодируем все ПВР один раз
        self.warmup_geocoding()

        all_pvr = self.load_pvr_from_knowledge_base()
        if not all_pvr:
            print("❌ [ROUTE] Список ПВР пуст!")
            return []

        print(f"📋 [ROUTE] Всего ПВР в базе: {len(all_pvr)}")

        geocoded = []
        coord_set = set()  # для диагностики уникальности координат

        for pvr in all_pvr:
            print(f"\n⏳ [ROUTE] Геокодирую: {pvr['name']} | {pvr['address']}")
            lat, lon = self.geocode_address(pvr["address"], pvr["district"])

            # ВАЖНО: проверяем на None, не на истинность (0.0 == False!)
            if lat is not None and lon is not None:
                coord_key = f"{lat:.4f},{lon:.4f}"
                coord_set.add(coord_key)
                dist = self.haversine_distance(user_lat, user_lon, lat, lon)
                print(f"   ✅ {pvr['name']}: ({lat}, {lon}) → {round(dist,1)} км | coord_key={coord_key}")
                geocoded.append({**pvr, "lat": lat, "lon": lon, "distance_km": round(dist, 1)})
            else:
                print(f"   ❌ Не удалось геокодировать: {pvr['address']}")

        print(f"\n📊 [ROUTE] Итог геокодирования:")
        print(f"   Успешно: {len(geocoded)}/{len(all_pvr)}")
        print(f"   Уникальных координат: {len(coord_set)}")
        if len(coord_set) == 1:
            print(f"   ⚠️  ВСЕ ПВР ИМЕЮТ ОДИНАКОВЫЕ КООРДИНАТЫ: {coord_set}")
        else:
            for c in list(coord_set)[:5]:
                print(f"   → {c}")

        geocoded.sort(key=lambda x: x["distance_km"])
        result = geocoded[:top_n]

        print(f"\n✅ [ROUTE] Топ-{top_n} ближайших ПВР:")
        for pvr in result:
            print(f"   📍 {pvr['name']} | ({pvr['lat']}, {pvr['lon']}) | {pvr['distance_km']} км")
        return result

    def geocode_user_address(self, address_text: str) -> tuple:
        """Геокодирует адрес пользователя через 2GIS / Яндекс."""
        cleaned = self._clean_user_address_text(address_text)
        lat, lon = self.geocode_address(cleaned, "user_input")
        if lat is not None:
            print(f"📍 [GEOCODE USER] '{address_text}' → '{cleaned}' → ({lat}, {lon})")
        else:
            print(f"❌ [GEOCODE USER] Не удалось геокодировать: '{address_text}' (очищено: '{cleaned}')")
        return (lat, lon)

    @staticmethod
    def _clean_user_address_text(text: str) -> str:
        """
        Убирает из реплики пользователя бытовые формулировки ("я в", "я нахожусь",
        "живу в", восклицательные знаки и т.п.), оставляя только сам адрес —
        это заметно повышает успешность геокодирования через 2GIS.
        """
        cleaned = text.strip()
        filler_patterns = [
            r"^я\s+(сейчас\s+)?(нахожусь\s+)?в\s+",
            r"^я\s+живу\s+в\s+",
            r"^живу\s+в\s+",
            r"^нахожусь\s+в\s+",
            r"^мой\s+адрес\s*[:\-]?\s*",
            r"^адрес\s*[:\-]?\s*",
        ]
        for pattern in filler_patterns:
            cleaned = re.sub(pattern, "", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"[!?]+$", "", cleaned).strip()
        return cleaned or text.strip()

    # ─────────────────────────────────────────────────────────────
    # AI ROUTER — обновлен для понимания контекста и адресов
    # ─────────────────────────────────────────────────────────────
    def analyze_query_intent(self, query: str, user_district: str = None, chat_id: str = "default") -> dict:
        print(f"\n🤖 [AI ROUTER] Анализ намерения запроса...")

        available_districts = list(DISTRICT_MAP.keys())

        # Добавляем контекст предыдущего сообщения, чтобы роутер понимал, что юзер отвечает адресом
        history_context = ""
        if chat_id in self.histories and self.histories[chat_id]:
            for msg in reversed(self.histories[chat_id]):
                if msg["role"] == "assistant":
                    history_context = f"\nКОНТЕКСТ ДИАЛОГА (ПРЕДЫДУЩИЙ ОТВЕТ БОТА):\n\"{msg['content']}\"\n"
                    break

        analysis_prompt = f"""Ты — интеллектуальный роутер запросов для системы информирования о паводках в Тюменской области («Паводок72»).

ТЕМАТИКА СИСТЕМЫ (и только она):
паводки, наводнения, подтопления; эвакуация и пункты временного размещения (ПВР); паводковая обстановка по районам; инструкции и рекомендации при угрозе ЧС; контакты экстренных служб; дороги, гидропосты, гуманитарная помощь, вакцинация — но ТОЛЬКО в контексте паводковой ситуации.

ДОСТУПНЫЕ ИСТОЧНИКИ ДАННЫХ:

1. РАЙОНЫ (сайт паводок72.рф):
{', '.join(available_districts)}

2. ЛОКАЛЬНАЯ БАЗА ЗНАНИЙ (data/*.txt) — всегда используется автоматически

{'МЕСТОПОЛОЖЕНИЕ ПОЛЬЗОВАТЕЛЯ: ' + user_district if user_district else ''}
{history_context}
ЗАПРОС ПОЛЬЗОВАТЕЛЯ: "{query}"

ШАГ 1 — ПРОВЕРКА ТЕМАТИКИ:
Определи "off_topic":
- true — если запрос НЕ относится к тематике системы
- false — если запрос относится к паводкам/эвакуации/ЧС/ПВР/обстановке в регионе, либо это приветствие/уточняющий вопрос в рамках темы диалога. Если запрос состоит преимущественно из адреса — это НЕ off_topic.

ШАГ 2 (только если off_topic = false) — АНАЛИЗ ИСТОЧНИКОВ:
- Если запрос про конкретный район → добавь этот район в "districts"
- Если есть местоположение пользователя и запрос про "где мне", "что делать", "какая обстановка" → needs_parsing: true, добавь район пользователя

ОПРЕДЕЛЕНИЕ МАРШРУТА (is_route_request):
Установи is_route_request = true, если:
- пользователь хочет построить маршрут до ПВР / пункта эвакуации
- узнает как добраться / как пройти до ПВР
- ВАЖНО: Если текст запроса состоит преимущественно из адреса (например: "Ишим, ул. Республики, 12", "с. Викулово", "Тюмень, Ямская 73") или пользователь явно отвечает адресом на предыдущий вопрос бота, ОБЯЗАТЕЛЬНО установи is_route_request = true.

ИЗВЛЕЧЕНИЕ АДРЕСА (user_address_in_query):
Если в тексте запроса явно указан адрес или название населённого пункта — извлеки его.
Примеры: "я нахожусь на ул. Ленина 5", "эвакуируюсь из с. Викулово", "живу в Тобольске на Октябрьской", "ул. Мира 12", "с. Абатское"
Если адреса нет — верни пустую строку "".

ФОРМАТ ОТВЕТА (строго JSON):
{{
    "off_topic": true/false,
    "needs_parsing": true/false,
    "is_route_request": true/false,
    "user_address_in_query": "адрес из текста или пустая строка",
    "districts": ["викулов", "тобольск"],
    "reasoning": "краткое обоснование (1-2 предложения)"
}}

ВАЖНО: возвращай ТОЛЬКО JSON, без дополнительного текста!"""

        try:
            response = self.client.chat.completions.create(
                model=self.model_name,
                messages=[{"role": "user", "content": analysis_prompt}],
                temperature=0.1
            )

            ai_response = response.choices[0].message.content.strip()

            if "```json" in ai_response:
                ai_response = ai_response.split("```json")[1].split("```")[0].strip()
            elif "```" in ai_response:
                ai_response = ai_response.split("```")[1].split("```")[0].strip()

            result = json.loads(ai_response)
            result.setdefault("off_topic", False)
            result.setdefault("is_route_request", False)
            result.setdefault("user_address_in_query", "")

            print(f"✅ [AI ROUTER] Анализ завершён:")
            print(f"   Не по теме: {result['off_topic']}")
            print(f"   Нужен парсинг: {result['needs_parsing']}")
            print(f"   Запрос маршрута: {result['is_route_request']}")
            print(f"   Адрес в запросе: '{result['user_address_in_query']}'")
            print(f"   Районы: {result.get('districts', [])}")
            print(f"   Обоснование: {result.get('reasoning', 'не указано')}")

            return result

        except Exception as e:
            print(f"❌ [AI ROUTER] Ошибка анализа: {e}")
            return {
                'off_topic': False,
                'needs_parsing': True,
                'is_route_request': False,
                'user_address_in_query': '',
                'districts': [],
                'reasoning': f"Ошибка ИИ-анализа: {str(e)}"
            }

    def save_debug_report(self, query, live_data, static_data, final_prompt):
        timestamp = datetime.datetime.now().strftime("%H-%M-%S")
        filename = f"logs/debug_{timestamp}.txt"
        with open(filename, "w", encoding="utf-8") as f:
            f.write(f"ЗАПРОС: {query}\n\n")
            f.write(f"=== ОПЕРАТИВНЫЕ ДАННЫЕ С САЙТА ===\n{live_data if live_data else 'НЕТ'}\n\n")
            f.write(f"=== ДАННЫЕ ИЗ ФАЙЛОВ (DATA/*.TXT) ===\n{static_data if static_data else 'НЕТ'}\n\n")
            f.write(f"=== ПОЛНЫЙ ПРОМПТ ДЛЯ ИИ ===\n{final_prompt}\n")

    def get_answer(self, query: str, chat_id: str = "default", lat: float = None, lon: float = None):
        print(f"\n{'=' * 60}")
        print(f"📨 [REQUEST] Новый запрос: {query}")

        # 1. Определяем район по координатам
        user_district = None
        if lat is not None and lon is not None:
            user_district = self.get_district_from_coords(lat, lon)
            if user_district is None:
                user_district = f"координаты {lat:.4f}, {lon:.4f}"
                print(f"⚠️  [GEO] Район не определён, используем координаты: {user_district}")
            print(f"📍 [GEO] Местоположение: {user_district} ({lat}, {lon})")

        # 1.5. Проверяем, не является ли это продолжением маршрутного диалога:
        if self._is_pending_route_address(chat_id):
            print(f"🔁 [ROUTE] Обнаружен ожидающий адрес для маршрута — "
                  f"принудительно направляем в обработчик маршрута")
            intent = {"is_route_request": True, "off_topic": False,
                      "user_address_in_query": query, "districts": []} # Исправлено: передаем query вместо пустой строки
            return self._handle_route_request(query, chat_id, lat, lon, intent)

        # 2. ИИ-анализ намерения (теперь передаем chat_id для учета контекста)
        intent = self.analyze_query_intent(query, user_district, chat_id)

        # ── ВЕТКА: ЗАПРОС НЕ ПО ТЕМЕ ──
        if intent.get("off_topic"):
            print(f"🚫 [ROUTER] Запрос не относится к теме паводков — отказ без генерации")
            refusal = (
                "Я — ИИ-консультант системы «Паводок72» и могу помочь только с вопросами "
                "о паводковой обстановке, эвакуации, пунктах временного размещения, "
                "дорогах и действиях при угрозе паводка в Тюменской области.\n\n"
                "Пожалуйста, задайте вопрос по этой теме — например: «какая обстановка "
                "в Викуловском районе?» или «что делать при угрозе паводка?»."
            )
            if chat_id not in self.histories:
                self.histories[chat_id] = []
            self.histories[chat_id].append({"role": "user", "content": query})
            self.histories[chat_id].append({"role": "assistant", "content": refusal})
            return refusal

        # ── ВЕТКА МАРШРУТА ──
        if intent.get("is_route_request"):
            return self._handle_route_request(query, chat_id, lat, lon, intent)

        # 3. Локальная база
        query_with_geo = f"{query} {user_district}" if user_district else query
        static_context = self.agent_search(query_with_geo)

        # 4. Парсинг внешних источников
        parsed_data = {}
        districts_to_parse = intent.get('districts', [])

        if user_district:
            matched_in_map = False
            user_district_lower = user_district.lower()
            for available_district in DISTRICT_MAP.keys():
                if available_district in user_district_lower:
                    matched_in_map = True
                    if available_district not in [d.lower() for d in districts_to_parse]:
                        districts_to_parse.append(available_district)
                        print(f"🎯 [ROUTER] Добавлен район пользователя: {available_district}")
                    else:
                        print(f"🎯 [ROUTER] Район пользователя уже в списке: {available_district}")
                    break

            if not matched_in_map:
                print(f"⚠️  [ROUTER] '{user_district}' не в DISTRICT_MAP — парсим все доступные районы")
                for available_district in DISTRICT_MAP.keys():
                    if available_district not in [d.lower() for d in districts_to_parse]:
                        districts_to_parse.append(available_district)

        if districts_to_parse:
            parsed_data = get_multiple_sources(districts_to_parse, [])

        # 5. Контекст
        context_parts = []
        if user_district:
            context_parts.append(f"📍 МЕСТОПОЛОЖЕНИЕ ПОЛЬЗОВАТЕЛЯ: {user_district} (Координаты: {lat}, {lon})")

        if parsed_data:
            for source_key, (text, url) in parsed_data.items():
                if text:
                    source_name = source_key.replace("district_", "").upper()
                    context_parts.append(
                        f"🌐 АКТУАЛЬНЫЕ ДАННЫЕ (РАЙОН: {source_name}, сайт паводок72.рф)\n"
                        f"Источник: {url}\n{text}"
                    )

        if static_context:
            context_parts.append(f"📚 СПРАВОЧНАЯ ИНФОРМАЦИЯ ИЗ БАЗЫ ЗНАНИЙ:\n{static_context}")

        combined_context = "\n\n" + "—" * 60 + "\n\n".join(context_parts) if context_parts else ""

        # 6. Промпт
        system_prompt = (
            "Ты — ИИ-помощник штаба МЧС (Паводок72). Твоя задача — давать точные, полные и структурированные ответы. Отвечай понятно, доступно\n\n"
            "КРИТИЧЕСКИ ВАЖНЫЕ ПРАВИЛА:\n"
            "1. Если в контексте есть АКТУАЛЬНЫЕ ДАННЫЕ с сайта паводок72.рф — используй их как ПРИОРИТЕТНЫЕ источники!\n"
            "2. Если передано МЕСТОПОЛОЖЕНИЕ ПОЛЬЗОВАТЕЛЯ — обязательно учитывай его при ответах на вопросы 'что мне делать', 'нужна ли эвакуация' и т.п.\n"
            "3. При запросе информации об объектах (ПВР, гидропосты, дороги) выводи ВСЕ найденные детали:\n"
            "   - Названия/адреса\n"
            "   - ФИО и телефоны ответственных лиц (если есть)\n"
            "   - Статус (открыт/закрыт/перекрыт)\n"
            "   - Вместимость, условия (для ПВР)\n"
            "4. Структурируй ответ для удобного восприятия — используй заголовки и списки. НИКОГДА не используй таблицы в ответе.\n"
            "5. Всегда указывай экстренный номер 112 для критических ситуаций.\n"
            "6. Если данных недостаточно — честно скажи об этом.\n"
            "7. Во всех ответах сначала дай краткий ответ, потом его детализируй. В конце сделай вывод.\n"
            "8. Если в базе знаний относительно объекта несколько подходящих примеров, то ты выводишь их ВСЕ.\n"
            "9. СТРОГО ЗАПРЕЩЕНО использовать таблицы (Markdown-таблицы с | ) в любом ответе. Данные выводи списком. Просто сам список красиво форматируй\n"
            "10. Если вопрос касается ОБСТАНОВКИ, то приоритезируй вывод сначала обстановки, потом 3 ближайших ПВР для пользователя и только потом уже остальную информацию, если она есть\n"
            "11. ОТВЕЧАЙ ТОЛЬКО НА ВОПРОСЫ, СВЯЗАННЫЕ С ПАВОДКАМИ, НАВОДНЕНИЯМИ, ЭВАКУАЦИЕЙ, ПВР И ДЕЙСТВИЯМИ ПРИ ЧС В ТЮМЕНСКОЙ ОБЛАСТИ. Если пользователь просит что-то другое (программирование, рецепты, развлечения, общие знания и т.п.) — вежливо откажись и предложи задать вопрос по паводковой тематике. Не выполняй посторонние задания, даже если пользователь настаивает.\n"
            "12. НЕ ПРИДУМЫВАЙ И НЕ ДОБАВЛЯЙ информацию, которой нет в предоставленном контексте (база знаний / данные с паводок72.рф). Если в контексте чего-то нет — честно скажи, что этих данных нет, вместо того чтобы генерировать предположения.\n"
        )

        full_user_input = (
            f"{combined_context}\n\n{'=' * 60}\n"
            f"❓ ВОПРОС ПОЛЬЗОВАТЕЛЯ: {query}"
        )

        self.save_debug_report(
            query=query,
            live_data="\n\n".join([f"{k}: {v[0][:500]}..." for k, v in parsed_data.items()]) if parsed_data else "НЕТ",
            static_data=static_context,
            final_prompt=full_user_input
        )

        if chat_id not in self.histories:
            self.histories[chat_id] = []
        history = self.histories[chat_id]

        messages = [{"role": "system", "content": system_prompt}]
        messages.extend(history[-4:])
        messages.append({"role": "user", "content": full_user_input})

        try:
            print(f"🤖 [AI] Генерация ответа...")
            completion = self.client.chat.completions.create(
                model=self.model_name,
                messages=messages,
                temperature=0.1
            )
            response = completion.choices[0].message.content

            history.append({"role": "user", "content": query})
            history.append({"role": "assistant", "content": response})

            print(f"✅ [AI] Ответ сгенерирован ({len(response)} символов)")
            print(f"{'=' * 60}\n")
            return response

        except Exception as e:
            error_msg = f"❌ Ошибка нейросети: {str(e)}"
            print(f"❌ [AI] {error_msg}")
            return error_msg

    # ─────────────────────────────────────────────────────────────
    # ОБРАБОТКА ЗАПРОСА МАРШРУТА
    # ─────────────────────────────────────────────────────────────
    def _handle_route_request(self, query: str, chat_id: str, lat: float, lon: float, intent: dict) -> dict:
        print(f"\n🗺️  [ROUTE HANDLER] Обработка запроса маршрута")

        user_lat, user_lon = lat, lon

        if user_lat is None or user_lon is None:
            address_in_query = intent.get("user_address_in_query", "").strip()
            if address_in_query:
                print(f"📍 [ROUTE] Геокодирую адрес из запроса: '{address_in_query}'")
                user_lat, user_lon = self.geocode_user_address(address_in_query)

        # Если координат всё ещё нет — проверяем историю чата:
        if user_lat is None or user_lon is None:
            user_lat, user_lon = self._extract_address_from_history(query, chat_id)

        if user_lat is None or user_lon is None:
            print(f"⚠️  [ROUTE] Координаты не определены, запрашиваем адрес")
            fallback_text = (
                "Чтобы построить маршрут до ближайшего пункта временного размещения, "
                "мне нужно знать ваше местоположение.\n\n"
                "Пожалуйста, напишите ваш текущий адрес (например: «с. Викулово, ул. Ленина, 5» "
                "или «г. Ишим, ул. Республики, 12»), и я сразу построю маршрут."
            )
            if chat_id not in self.histories:
                self.histories[chat_id] = []
            self.histories[chat_id].append({"role": "user", "content": query})
            self.histories[chat_id].append({"role": "assistant", "content": fallback_text})
            return {"response": fallback_text, "route_data": None}

        nearest_pvr = self.get_nearest_pvr(user_lat, user_lon, top_n=3)

        if not nearest_pvr:
            return {
                "response": (
                    "К сожалению, не удалось найти данные о пунктах временного размещения. "
                    "Позвоните на горячую линию: 122 или 112."
                ),
                "route_data": None
            }

        pvr_descriptions = []
        for i, pvr in enumerate(nearest_pvr, 1):
            pvr_descriptions.append(
                f"{i}. **{pvr['name']}**\n"
                f"   📍 {pvr['address']}\n"
                f"   📏 Расстояние: ~{pvr['distance_km']} км\n"
                f"   👥 Вместимость: {pvr['capacity']} чел.\n"
                f"   📞 {pvr['phone'] or 'телефон не указан'}"
            )

        response_text = (
            "Я нашёл 3 ближайших пункта временного размещения. "
            "Выберите один — и я построю маршрут прямо здесь:\n\n" +
            "\n\n".join(pvr_descriptions)
        )

        route_data = {
            "type": "pvr_selection",
            "user_location": {"lat": user_lat, "lon": user_lon},
            "destinations": [
                {
                    "name": pvr["name"],
                    "address": pvr["address"],
                    "district": pvr["district"],
                    "lat": pvr["lat"],
                    "lon": pvr["lon"],
                    "distance_km": pvr["distance_km"],
                    "phone": pvr.get("phone", ""),
                    "capacity": pvr.get("capacity", 0)
                }
                for pvr in nearest_pvr
            ]
        }

        if chat_id not in self.histories:
            self.histories[chat_id] = []
        self.histories[chat_id].append({"role": "user", "content": query})
        self.histories[chat_id].append({"role": "assistant", "content": response_text})

        print(f"✅ [ROUTE HANDLER] Ответ сформирован, {len(nearest_pvr)} ПВР")
        return {"response": response_text, "route_data": route_data}

    def _is_pending_route_address(self, chat_id: str) -> bool:
        """
        True, если последним сообщением ассистента в чате был запрос адреса
        для построения маршрута (т.е. мы ждём от пользователя адрес).
        """
        history = self.histories.get(chat_id, [])
        if not history:
            return False

        last_assistant_messages = [
            m["content"] for m in history[-2:]
            if m["role"] == "assistant"
        ]
        return any(
            "напишите ваш текущий адрес" in msg or "ваше местоположение" in msg
            for msg in last_assistant_messages
        )

    def _extract_address_from_history(self, current_query: str, chat_id: str) -> tuple:
        """
        Если в истории бот уже просил адрес (fallback-сообщение маршрута),
        то текущее сообщение пользователя может быть ответом с адресом.
        Геокодируем его напрямую.
        """
        history = self.histories.get(chat_id, [])
        if not history:
            return (None, None)

        # Проверяем: последнее сообщение ассистента содержит просьбу указать адрес
        last_assistant_messages = [
            m["content"] for m in history[-4:]
            if m["role"] == "assistant"
        ]
        address_was_requested = any(
            "напишите ваш текущий адрес" in msg or "ваше местоположение" in msg
            for msg in last_assistant_messages
        )

        if not address_was_requested:
            return (None, None)

        # Текущий запрос — вероятно, адрес. Пробуем геокодировать.
        print(f"📍 [ROUTE] История: бот просил адрес, геокодирую ответ пользователя: '{current_query}'")
        lat, lon = self.geocode_user_address(current_query)
        return (lat, lon)
