#!/usr/bin/env python3
"""Root launcher for the SoarTV Liquid Glass Desktop Setup Studio."""
from pathlib import Path
import runpy
import sys

gui_dir = Path(__file__).resolve().parent / "SoarTV-launcher" / "tools" / "setup_gui"
sys.path.insert(0, str(gui_dir))
runpy.run_path(str(gui_dir / "wizard_app.py"), run_name="__main__")
