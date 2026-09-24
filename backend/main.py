from fastapi import FastAPI
from pydantic import BaseModel
from typing import Optional, List, Any
import uvicorn
from rag_engine import FloodAssistant

from flood_parser import set_cache_ttl
set_cache_ttl(45 * 60)

app = FastAPI()
assistant = FloodAssistant()


class ChatRequest(BaseModel):
    message: str
    chat_id: str = "default"
    lat: Optional[float] = None
    lon: Optional[float] = None


class PvrDestination(BaseModel):
    name: str
    address: str
    district: str
    lat: float
    lon: float
    distance_km: float
    phone: str = ""
    capacity: int = 0


class UserLocation(BaseModel):
    lat: float
    lon: float


class RouteData(BaseModel):
    type: str                          # "pvr_selection" | "route_map"
    user_location: UserLocation
    destinations: List[PvrDestination]


class ChatResponse(BaseModel):
    response: str
    route_data: Optional[RouteData] = None


@app.get("/")
async def root():
    return {"status": "Server with Live Debugging is Online"}


@app.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    print(f"\n[RECEIVED] Новый вопрос: {request.message}")
    if request.lat and request.lon:
        print(f"[GEO] Координаты пользователя: {request.lat}, {request.lon}")

    result = assistant.get_answer(
        query=request.message,
        chat_id=request.chat_id,
        lat=request.lat,
        lon=request.lon
    )

    # get_answer возвращает либо строку (обычный ответ),
    # либо dict с ключами response + route_data (маршрутный ответ)
    if isinstance(result, dict):
        return ChatResponse(
            response=result["response"],
            route_data=result.get("route_data")
        )
    else:
        return ChatResponse(response=result)


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)