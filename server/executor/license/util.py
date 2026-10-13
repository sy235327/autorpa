# [pigeon-fork] license 工具：卡码生成/校验、批次号、会话 token
#encoding=utf-8
import random
import time

from ..utils import r_string

# 去易混淆字符（无 0/O、1/I/L）
ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
GROUPS = 4
GROUP_LEN = 4


def _checksum(text):
    total = sum((i + 1) * ALPHABET.index(c) for i, c in enumerate(text))
    return ALPHABET[total % len(ALPHABET)]


def gen_card_code():
    """生成 XXXX-XXXX-XXXX-XXXX 格式卡码（末位为校验位）"""
    body = "".join(random.choice(ALPHABET)
                   for _ in range(GROUPS * GROUP_LEN - 1))
    raw = body + _checksum(body)
    return "-".join(raw[i:i + GROUP_LEN]
                    for i in range(0, len(raw), GROUP_LEN))


def check_card_code(code):
    raw = "".join(c for c in (code or "").strip().upper() if c.isalnum())
    if len(raw) != GROUPS * GROUP_LEN:
        return False
    if any(c not in ALPHABET for c in raw):
        return False
    return _checksum(raw[:-1]) == raw[-1]


def gen_batch_no():
    return time.strftime("B%y%m%d-") + r_string(6).upper()


def gen_session_token():
    return r_string()


def now():
    return int(time.time())
