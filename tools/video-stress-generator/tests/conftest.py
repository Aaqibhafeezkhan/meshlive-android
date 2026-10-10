import pytest

from videostress.media import environment


@pytest.fixture(scope="session")
def env():
    return environment()
