#!/usr/bin/env python3
"""
Entry point for the bundled fontmake binary.

This script wraps fontmake and adds WOFF/WOFF2 compression support.
"""
import sys


def compress_font(format_type, input_path, output_path):
    """Compress a font file to WOFF or WOFF2 format."""
    from fontTools.ttLib import TTFont

    if format_type == 'woff':
        font = TTFont(input_path)
        font.flavor = 'woff'
        font.save(output_path)
    elif format_type == 'woff2':
        from fontTools.ttLib.woff2 import compress
        compress(input_path, output_path)
    else:
        print(f'Unknown compression format: {format_type}', file=sys.stderr)
        return 1
    return 0


def main():
    # Handle --compress command for WOFF/WOFF2 conversion
    if len(sys.argv) >= 2 and sys.argv[1] == '--compress':
        if len(sys.argv) != 5:
            print('Usage: fontmake --compress <woff|woff2> <input> <output>', file=sys.stderr)
            return 1
        format_type = sys.argv[2]
        input_path = sys.argv[3]
        output_path = sys.argv[4]
        return compress_font(format_type, input_path, output_path)

    # Otherwise, run fontmake normally
    from fontmake.__main__ import main as fontmake_main
    return fontmake_main()


if __name__ == '__main__':
    raise SystemExit(main())
