#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
步道乐跑 协议跑器（api2.lptiyu.com/v3/api.php/...）
=====================================================
免模拟器方案：登录一次拿 token，之后所有跑步数据走纯 HTTP 协议上传。

协议来源（反编译 4.7 版 com.lptiyu.tanke 证实）：
  - 公共请求体参数      : kd.h.e()（kd/h.java:45）version/version_name/mobileModel/
                         mobileDeviceId/mobileOsVersion/ostype/school_id/uid/token/
                         timestamp/nonce，每个请求都带。
  - 取验证码            : Login/getCodeV300  入参 phone + type（kd/o.java:922）
  - 快捷登录            : Login/quickLoginV339  入参 phone + code + type=1 [+ app_token
                         + school_id 免验证码通道]（kd/o.java:923, bd/e.java:34）
  - 跑步前置            : Run2/beforeRunV260 → BeforeRunResponse（含 game_id、
                         run_face_verify_config、android_cheats）
  - 位置上报（跑步中）  : Run/setRunLocationRecord  入参 latitude/longitude/game_id/point_ids
                         （school_run/c.java:354）
  - 结束跑步            : Run/stopRunV278  入参 start_time/distance/game_id/point_ids
  - 人脸核验            : Run2/faceVerify（可选；服务端比对基准脸，见 App 内
                         face_fixed_image hook 的等价物：这里用「跳过」或
                         face_img 参数（quickLogin 也接受 face_img））

「别人一个 id 就能跑」= 学校 app_token 通道 或 短信验证码登录一次后缓存 token。

配置 config.json（与 tools/lepao-login.html 导出的账户 JSON 互通）：
{
  "phone": "13800000000",
  "school_id": "12345",
  "app_token": "学校门户发的 token（免验证码通道，可空）",
  "code": "短信验证码（app_token 为空时必填）",
  "device": {                       // 设备指纹，决定服务端环境判定
    "version": "4.7.0",
    "version_name": "4.7.0",
    "mobile_model": "SM-G9810",
    "mobile_device_id": "oaid-xxxx-xxxx",   // 与乐跑手机一致最好
    "mobile_os_version": "13",
    "ostype": "1"
  },
  "run": {
    "distance_km": 3.0,            // 本次要跑的距离
    "speed_kmh": 6.0,             // 模拟配速
    "start_lat": 30.5720, "start_lng": 104.0660,
    "points": [                   // 路线点（GCJ-02），按顺序走
      [30.5720, 104.0660], [30.5725, 104.0668], ...
    ],
    "capture_interval_s": 60,     // 位置上报间隔
    "face_img": ""                // 人脸核验照片（OSS 上传后的 file key，可空=跳过）
  }
}

用法：
  python lepao-runner.py login    config.json   # 登录并缓存 token
  python lepao-runner.py run     config.json    # 完整跑一次（beforeRun→上报→stopRun）
  python lepao-runner.py before  config.json    # 只取 game_id
  python lepao-runner.py stop    config.json

token 缓存在 .lepao_token.json；quickLogin 成功后 refresh_token 也在里面，
过期后用 RefreshToken 接口换新。
"""
import json, sys, time, uuid, random, threading, hashlib
import urllib.request, urllib.parse, ssl

BASE = "https://api2.lptiyu.com/v3/api.php/"   # kd/o.java:447 (q=2 线上)
API = {
    "get_code":  "Login/getCodeV300",
    "quick":     "Login/quickLoginV339",
    "refresh":   "Login/RefreshToken",
    "before":    "Run2/beforeRunV260",
    "location":  "Run/setRunLocationRecord",
    "stop":      "Run/stopRunV278",
    "sim":       "Run2/simVerify",
}

def post(path, body, raw=False):
    data = urllib.parse.urlencode(body).encode()
    req = urllib.request.Request(BASE + path, data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded; charset=utf-8",
                 "Connection": "close",
                 "User-Agent": " okhttp/4.9.3"})
    ctx = ssl.create_default_context()
    with urllib.request.urlopen(req, context=ctx, timeout=15) as r:
        return json.loads(r.read().decode())

def common(cfg):
    """kd.h.e() 的公共参数（每个 POST 都要带）"""
    d = cfg.get("device", {})
    return {
        "version": d.get("version", "4.7.0"),
        "version_name": d.get("version_name", "4.7.0"),
        "mobileModel": d.get("mobile_model", "SM-G9810"),
        "mobileDeviceId": d.get("mobile_device_id", ""),
        "mobileOsVersion": d.get("mobile_os_version", "13"),
        "ostype": d.get("ostype", "1"),
        "school_id": str(cfg.get("school_id", "")),
        "timestamp": str(int(time.time())),
        "nonce": uuid.uuid4().hex,      # z0.a()（VMP 内随机串），uuid4 等价
    }

def load_token(cfg):
    try:
        with open(".lepao_token.json") as f:
            t = json.load(f)
            if t.get("phone") == cfg.get("phone") and t.get("token"):
                return t
    except Exception:
        pass
    return None

def save_token(cfg, token, refresh=""):
    json.dump({"phone": cfg.get("phone"), "token": token,
               "refresh_token": refresh, "ts": int(time.time())},
              open(".lepao_token.json", "w"))

def login(cfg, interactive_code=True):
    """quickLogin：优先 app_token 通道，其次 手机+验证码"""
    body = common(cfg)
    body.update({"phone": str(cfg.get("phone", "")), "type": "1"})
    app_token = cfg.get("app_token", "")
    if app_token:
        body["app_token"] = app_token
        body.pop("school_id", None)
        body["school_id"] = str(cfg.get("school_id", ""))
    else:
        code = cfg.get("code", "")
        if not code and interactive_code:
            print("→ 先取验证码：")
            print(post("Login/getCodeV300", {**common(cfg), "phone": str(cfg.get("phone"))}))
            code = input("短信验证码：").strip()
        if not code:
            raise SystemExit("无验证码且无 app_token，无法登录")
        body["code"] = code
    rsp = post(API["quick"], body)
    data = (rsp or {}).get("data") or {}
    if not data:
        raise SystemExit(f"quickLogin 失败: {json.dumps(rsp, ensure_ascii=False)}")
    save_token(cfg, data.get("access_token", ""), data.get("refresh_token", ""))
    print("✓ 登录成功，uid =", data.get("uid", data))
    return data

def before(cfg):
    t = load_token(cfg) or login(cfg, interactive_code=False)
    body = common(cfg)
    body["uid"] = str(cfg.get("uid") or "")      # 登录响应的 uid
    body["token"] = t["token"]
    rsp = post(API["before"], body)
    data = (rsp or {}).get("data") or {}
    print("beforeRun →", json.dumps(data, ensure_ascii=False)[:400])
    if data.get("run_face_verify_config"):
        print("⚠ 服务端要求人脸核验：", data["run_face_verify_config"],
              "（协议端暂不处理；短距离/无核验配置的学校可跳过）")
    return data

def simulate_run(cfg, game_id):
    """beforeRun 成功后按 points 路线 + 配速逐点上报 setRunLocationRecord，
    最后 stopRun。points 是 GCJ-02 [lat,lng] 列表。"""
    t = load_token(cfg)
    run = cfg.get("run", {})
    pts = run.get("points") or []
    km = float(run.get("distance_km", 3.0))
    kmh = float(run.get("speed_kmh", 6.0))
    total_s = km * 1000 / kmh * 3.6
    start_time = int(time.time())
    n = len(pts)
    interval = int(run.get("capture_interval_s", 60))
    print(f"模拟开始：{km}km @ {kmh}km/h，{n} 个路线点，总时长约 {total_s/60:.0f} 分钟")
    if n < 2:
        print("路线点不足，跳过上报循环（只 stop）")
        return start_time, km
    step_s = total_s / max(n - 1, 1)
    # 真实感：每 interval 秒上报一次，位置按配速沿折线插值
    for i in range(1, n + 1):
        frac = i / n
        # 取折线上 frac 总路程处的点
        target_idx = min(int(frac * (n - 1)), n - 1)
        lat, lng = pts[target_idx][0], pts[target_idx][1]
        # ±15m 抖动
        lat += random.uniform(-0.00012, 0.00012)
        lng += random.uniform(-0.00012, 0.00012)
        body = common(cfg)
        body["uid"] = str(cfg.get("uid") or "")
        body["token"] = t["token"]
        body.update({
            "latitude": f"{lat:.6f}",
            "longitude": f"{lng:.6f}",
            "game_id": str(game_id),
            "point_ids": "",     # App 里是服务端下发 capture point id 列表，首版留空
        })
        rsp = post(API["location"], body)
        ok = (rsp or {}).get("status") == 1
        print(f"  [{i}/{n}] ({lat:.5f},{lng:.5f}) → status={rsp.get('status')}")
        if not ok:
            print("  ⚠ 上报异常：", json.dumps(rsp, ensure_ascii=False)[:300])
        wait = max(step_s - interval, 2)   # 按配速推进
        print(f"  等待 {wait:.0f}s（总时长模拟，可用 Ctrl-C 中断后 stop）")
        time.sleep(min(wait, 5 if not ok else wait))
    # 结束
    body = common(cfg)
    body["uid"] = str(cfg.get("uid") or "")
    body["token"] = t["token"]
    body.update({
        "start_time": str(start_time),
        "distance": str(int(km * 1000)),
        "game_id": str(game_id),
        "point_ids": "",
    })
    rsp = post(API["stop"], body)
    print("stopRun →", json.dumps(rsp, ensure_ascii=False)[:300])
    return start_time, km

def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return
    cmd, cfg_path = sys.argv[1], sys.argv[2]
    cfg = json.load(open(cfg_path, encoding="utf-8"))
    if cmd == "login":
        login(cfg)
    elif cmd == "before":
        data = before(cfg)
        cfg["game_id"] = data.get("game_id")
        json.dump(cfg, open(cfg_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    elif cmd == "stop":
        cfg["uid"] = cfg.get("uid", "")
        simulate_run(cfg, cfg.get("game_id", ""))
    elif cmd == "run":
        data = before(cfg)
        cfg["game_id"] = data.get("game_id")
        cfg["uid"] = cfg.get("uid", "")
        json.dump(cfg, open(cfg_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        simulate_run(cfg, data.get("game_id", ""))
    else:
        print("未知命令", cmd)

if __name__ == "__main__":
    main()
