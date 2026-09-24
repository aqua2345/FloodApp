import requests
from bs4 import BeautifulSoup
import time
import re
from typing import Tuple, Optional, List, Dict
from urllib3.exceptions import InsecureRequestWarning

# Отключаем предупреждения о небезопасном SSL (для обхода проблем с сертификатами)
requests.packages.urllib3.disable_warnings(InsecureRequestWarning)

# Карта районов и соответствующих ссылок
DISTRICT_MAP = {
    "викулов": "vikulovskii",
    "тобольск": "tobolskiy",
    "уват": "uvatskiy",
    "абатск": "abatskii",
    "казанск": "kazanskyi",
    "ишим": "ishim"
}

# Карта разделов ГИС (ключевые слова -> URL раздела)
GIS_SECTIONS = {
    "перекрыт": "virtual1",  # перекрытые дороги
    "закрыт": "virtual1",
    "открыт": "virtual",  # открытые дороги
    "гидропост": "hydroposts",
    "гуманитарн": "sbor_gumanitarnoj_pomoshhi",
    "размещен": "punkty_vremennogo_razmeshhenija",
    "пвр": "punkty_vremennogo_razmeshhenija",
    "вакцинац": "punkty_vakcinacii_protiv_gepatita_a",
    "гепатит": "punkty_vakcinacii_protiv_gepatita_a",
}

BASE_URL = "https://паводок72.рф"
GIS_BASE_URL = "https://gis.72to.ru/orbismap/public_map/geoportal72/map29/text"

# ============================================================
# КЭШИРОВАНИЕ РЕЗУЛЬТАТОВ ПАРСИНГА
# ============================================================
# Время жизни кэша в секундах (по умолчанию 10 минут)
CACHE_TTL = 10 * 60

# Хранилище: { cache_key: {"data": (...), "timestamp": float} }
_parse_cache: Dict[str, dict] = {}


def _cache_get(key: str) -> Optional[Tuple]:
    """Возвращает данные из кэша если они ещё свежие, иначе None."""
    entry = _parse_cache.get(key)
    if entry is None:
        return None
    age = time.time() - entry["timestamp"]
    if age > CACHE_TTL:
        print(f"⏰ [CACHE] Кэш устарел для '{key}' (возраст {age:.0f}с > TTL {CACHE_TTL}с)")
        del _parse_cache[key]
        return None
    print(f"✅ [CACHE] Кэш HIT для '{key}' (возраст {age:.0f}с, TTL {CACHE_TTL}с)")
    return entry["data"]


def _cache_set(key: str, data: Tuple) -> None:
    """Сохраняет данные в кэш."""
    _parse_cache[key] = {"data": data, "timestamp": time.time()}
    print(f"💾 [CACHE] Сохранено в кэш: '{key}'")


def set_cache_ttl(seconds: int) -> None:
    """Позволяет изменить TTL кэша на ходу (вызывается из main.py при необходимости)."""
    global CACHE_TTL
    CACHE_TTL = seconds
    print(f"⚙️  [CACHE] TTL обновлён: {seconds}с")


def clear_cache(key: str = None) -> None:
    """Очищает весь кэш или конкретный ключ."""
    global _parse_cache
    if key:
        _parse_cache.pop(key, None)
        print(f"🗑️  [CACHE] Удалён ключ: '{key}'")
    else:
        _parse_cache.clear()
        print("🗑️  [CACHE] Кэш полностью очищен")
# ============================================================


# Создаём сессию для GIS запросов с отключенной проверкой SSL
gis_session = requests.Session()
gis_session.verify = False  # Отключаем проверку SSL сертификатов

# Настраиваем адаптер с повторными попытками
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

retry_strategy = Retry(
    total=3,  # Всего 3 попытки
    backoff_factor=1,  # Задержка между попытками: 1, 2, 4 секунды
    status_forcelist=[429, 500, 502, 503, 504],  # Коды для повтора
)
adapter = HTTPAdapter(max_retries=retry_strategy)
gis_session.mount("http://", adapter)
gis_session.mount("https://", adapter)

# Заголовки браузера для обхода блокировок
HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
    'Accept-Language': 'ru-RU,ru;q=0.9,en;q=0.8',
    'Accept-Encoding': 'gzip, deflate, br',
    'Connection': 'keep-alive',
    'Upgrade-Insecure-Requests': '1',
    'Sec-Fetch-Dest': 'document',
    'Sec-Fetch-Mode': 'navigate',
    'Sec-Fetch-Site': 'none',
    'Cache-Control': 'max-age=0'
}


def detect_query_type(query: str) -> str:
    """Определяет тип запроса: район или раздел ГИС"""
    query_lower = query.lower()

    # Сначала проверяем разделы ГИС (более специфичные запросы)
    for keyword in GIS_SECTIONS.keys():
        if keyword in query_lower:
            return "gis_section"

    # Потом проверяем районы
    for district in DISTRICT_MAP.keys():
        if district in query_lower:
            return "district"

    return "unknown"


def parse_gis_section(section_slug: str, max_pages: int = 5) -> Tuple[Optional[str], Optional[str]]:
    """
    Парсит раздел ГИС с автоматической пагинацией.
    Возвращает (объединённый_текст, базовая_ссылка)
    """
    cache_key = f"gis_{section_slug}"
    cached = _cache_get(cache_key)
    if cached is not None:
        return cached

    base_url = f"{GIS_BASE_URL}/{section_slug}/"
    all_data = []

    print(f"\n{'—' * 40}")
    print(f"🗺️  [GIS PARSER] Раздел: {section_slug}")

    for page in range(1, max_pages + 1):
        if page == 1:
            url = base_url
        else:
            url = f"{base_url}?page={page}"

        print(f"📄 [GIS PARSER] Загрузка страницы {page}: {url}")

        try:
            # Используем сессию с отключенной проверкой SSL
            response = gis_session.get(url, headers=HEADERS, timeout=15, verify=False)

            if response.status_code == 404:
                print(f"⚠️  [GIS PARSER] Страница {page} не найдена, останавливаемся")
                break

            if response.status_code != 200:
                print(f"❌ [GIS PARSER] Ошибка: статус {response.status_code}")
                continue

        except requests.exceptions.SSLError as ssl_err:
            # Фоллбэк: пробуем через обычный requests без сессии
            print(f"⚠️  [GIS PARSER] SSL-ошибка, пробуем альтернативный метод...")
            try:
                response = requests.get(url, headers=HEADERS, timeout=15, verify=False)
                if response.status_code != 200:
                    print(f"❌ [GIS PARSER] Альтернативный метод не помог: {response.status_code}")
                    continue
            except Exception as fallback_err:
                print(f"❌ [GIS PARSER] Критическая ошибка SSL: {fallback_err}")
                break

        except Exception as e:
            print(f"❌ [GIS PARSER] Ошибка на странице {page}: {e}")
            break

        try:
            response.encoding = 'utf-8'
            soup = BeautifulSoup(response.text, 'html.parser')

            # Удаляем навигацию и служебные элементы
            for element in soup(["script", "style", "nav", "footer", "header", "aside"]):
                element.extract()

            # Извлекаем текст
            raw_text = soup.get_text(separator='\n')
            lines = [line.strip() for line in raw_text.splitlines() if line.strip()]
            clean_text = '\n'.join(lines)

            if clean_text:
                all_data.append(f"=== СТРАНИЦА {page} ===\n{clean_text}")
                print(f"✅ [GIS PARSER] Страница {page}: {len(clean_text)} символов")
            else:
                print(f"⚠️  [GIS PARSER] Страница {page} пуста, останавливаемся")
                break

            # Проверяем наличие ссылки на следующую страницу
            next_page_link = soup.find('a', text=re.compile(r'следующ|next|»|›', re.I))
            if not next_page_link:
                print(f"ℹ️  [GIS PARSER] Дополнительных страниц нет")
                break

            time.sleep(0.5)  # Задержка между запросами

        except Exception as parse_err:
            print(f"❌ [GIS PARSER] Ошибка парсинга на странице {page}: {parse_err}")
            break

    if all_data:
        combined = '\n\n'.join(all_data)
        # Ограничиваем до 8000 символов (больше чем у районов, т.к. это структурированные данные)
        final_text = combined[:8000]
        print(f"✅ [GIS PARSER] Собрано {len(all_data)} страниц ({len(final_text)} симв.)")
        print(f"{'—' * 40}\n")
        result = (final_text, base_url)
        _cache_set(cache_key, result)
        return result
    else:
        print(f"❌ [GIS PARSER] Данные не получены")
        print(f"{'—' * 40}\n")
        return None, base_url


def get_district_info(query: str) -> Tuple[Optional[str], Optional[str]]:
    """Парсит страницу района (старая логика)"""
    query_lower = query.lower()
    target_slug = None
    district_name = ""

    for key, slug in DISTRICT_MAP.items():
        if key in query_lower:
            target_slug = slug
            district_name = key.capitalize()
            break

    if not target_slug:
        return None, None

    cache_key = f"district_{target_slug}"
    cached = _cache_get(cache_key)
    if cached is not None:
        return cached

    full_url = f"{BASE_URL}/{target_slug}"

    print(f"\n{'—' * 40}")
    print(f"📡 [PARSER] Обнаружен район: {district_name}")
    print(f"📡 [PARSER] Запрос к: {full_url}")

    start_time = time.time()
    try:
        response = requests.get(full_url, headers=HEADERS, timeout=10)
        response.encoding = 'utf-8'

        if response.status_code != 200:
            print(f"❌ [PARSER] Ошибка сайта: статус {response.status_code}")
            return None, full_url

        soup = BeautifulSoup(response.text, 'html.parser')

        # Вырезаем ненужные элементы
        for element in soup(["script", "style", "nav", "footer", "header", "aside"]):
            element.extract()

        # Получаем текст
        raw_text = soup.get_text(separator='\n')
        lines = [line.strip() for line in raw_text.splitlines() if line.strip()]
        clean_text = '\n'.join(lines)

        # Берем первые 5000 символов
        final_data = clean_text[:5000]

        elapsed = time.time() - start_time
        print(f"✅ [PARSER] Данные получены за {elapsed:.2f} сек. ({len(final_data)} симв.)")
        print(f"{'—' * 40}\n")

        result = (final_data, full_url)
        _cache_set(cache_key, result)
        return result

    except Exception as e:
        print(f"❌ [PARSER] Критическая ошибка: {e}")
        return None, full_url


def get_flood_info_if_needed(query: str) -> Tuple[Optional[str], Optional[str]]:
    """
    Главная функция парсинга (старая версия для обратной совместимости).
    Определяет тип запроса и вызывает соответствующий парсер.
    Возвращает (чистый_текст, ссылка) или (None, None).
    """
    query_type = detect_query_type(query)

    if query_type == "gis_section":
        # Определяем нужный раздел ГИС
        query_lower = query.lower()
        for keyword, section_slug in GIS_SECTIONS.items():
            if keyword in query_lower:
                return parse_gis_section(section_slug)
        return None, None

    elif query_type == "district":
        # Парсим страницу района
        return get_district_info(query)

    else:
        # Неизвестный тип запроса
        return None, None


def get_multiple_sources(districts: List[str], gis_sections: List[str]) -> Dict[
    str, Tuple[Optional[str], Optional[str]]]:
    """
    Парсит множественные источники одновременно.

    Args:
        districts: Список районов для парсинга (названия, например: ["викулов", "тобольск"])
        gis_sections: Список разделов ГИС для парсинга (ключевые слова, например: ["пвр", "гидропост"])

    Returns:
        Словарь с результатами: {
            'district_викулов': (text, url),
            'gis_пвр': (text, url),
            ...
        }
    """
    results = {}

    print(f"\n{'=' * 50}")
    print(f"🔍 [MULTI-PARSER] Запуск множественного парсинга")
    print(f"📍 Районы: {districts}")
    print(f"🗺️  Разделы ГИС: {gis_sections}")
    print(f"{'=' * 50}")

    # Парсим районы
    for district in districts:
        district_lower = district.lower()
        if district_lower in DISTRICT_MAP:
            print(f"\n🏘️  [MULTI-PARSER] Парсинг района: {district}")
            text, url = get_district_info(district)
            if text:
                results[f'district_{district_lower}'] = (text, url)
                print(f"✅ [MULTI-PARSER] Район {district}: получено {len(text)} символов")
            else:
                print(f"⚠️  [MULTI-PARSER] Район {district}: данные не получены")
        else:
            print(f"⚠️  [MULTI-PARSER] Неизвестный район: {district}")

    # Парсим разделы ГИС
    for section_keyword in gis_sections:
        section_lower = section_keyword.lower()
        matched_section = None

        # Находим соответствующий slug раздела
        for keyword, section_slug in GIS_SECTIONS.items():
            if keyword in section_lower or section_lower in keyword:
                matched_section = section_slug
                break

        if matched_section:
            print(f"\n🗺️  [MULTI-PARSER] Парсинг ГИС раздела: {section_keyword}")
            text, url = parse_gis_section(matched_section)
            if text:
                results[f'gis_{section_keyword.lower()}'] = (text, url)
                print(f"✅ [MULTI-PARSER] ГИС {section_keyword}: получено {len(text)} символов")
            else:
                print(f"⚠️  [MULTI-PARSER] ГИС {section_keyword}: данные не получены")
        else:
            print(f"⚠️  [MULTI-PARSER] Неизвестный раздел ГИС: {section_keyword}")

    print(f"\n{'=' * 50}")
    print(f"✅ [MULTI-PARSER] Парсинг завершён. Получено источников: {len(results)}")
    print(f"{'=' * 50}\n")

    return results