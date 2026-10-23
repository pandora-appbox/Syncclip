"""Use an already-enrolled desktop credential for explicitly requested live probes."""

import json
import os
from pathlib import Path


def load_config():
    path = Path(
        os.environ.get(
            "SYNCCLIP_TEST_CONFIG", Path.home() / ".config/syncclip/config.json"
        )
    )
    config = json.loads(path.read_text())
    if (
        not config.get("device_token")
        or not config.get("enrolled")
        or config.get("revoked")
    ):
        raise ValueError(
            "Set SYNCCLIP_TEST_CONFIG to an enrolled device's private config.json"
        )
    return config


def authorization(config):
    return "Bearer " + config["device_token"]
