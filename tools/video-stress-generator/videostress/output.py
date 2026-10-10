"""Fresh output reservation and cleanup of only registered unfinished files."""

from contextlib import contextmanager
from pathlib import Path


class OwnedOutput:
    def __init__(self,path):
        self.path = Path(path).resolve()
        self.path.mkdir(parents=True,exist_ok=False)
        self.files = []
        self.directories = {self.path}

    def file(self,relative):
        target = self.path/relative
        if not target.resolve().is_relative_to(self.path) or target.resolve()==self.path:
            raise ValueError("Output must stay within the exact reserved directory")
        parent = target.parent
        while parent != self.path:
            self.directories.add(parent)
            parent = parent.parent
        target.parent.mkdir(parents=True,exist_ok=True)
        self.files.append(target)
        return target

    def cleanup(self):
        # Revalidate every resolved target; never follow changed directories/symlinks.
        if self.path.resolve()!=self.path or self.path.is_symlink():
            return
        for target in reversed(self.files):
            if target.parent.resolve().is_relative_to(self.path):
                if target.is_file() or target.is_symlink():
                    target.unlink()
        for directory in sorted(self.directories,key=lambda p:len(p.parts),reverse=True):
            if directory.resolve().is_relative_to(self.path) and not directory.is_symlink():
                try:
                    directory.rmdir()
                except (FileNotFoundError,OSError):
                    pass  # Any unregistered/user-created file is preserved.


@contextmanager
def fresh_output(path):
    owned = OwnedOutput(path)
    try:
        yield owned
    except BaseException:
        owned.cleanup()
        raise
