#!/usr/bin/env python3
"""
Edge TTS wrapper - called by Java ProcessBuilder
"""

import sys
import argparse
import asyncio
import edge_tts


async def text_to_speech(text, output_path, voice="en-IN-NeerjaNeural", rate="+0%"):
    try:
        communicate = edge_tts.Communicate(text, voice, rate=rate)
        await communicate.save(output_path)
        print(f"SUCCESS: {output_path}")
        return True
    except Exception as e:
        print(f"ERROR: {str(e)}", file=sys.stderr)
        return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("text", help="Text to speak")
    parser.add_argument("--output", required=True, help="Output .mp3 path")
    parser.add_argument("--voice", default="en-IN-NeerjaNeural")
    parser.add_argument("--rate", default="+0%")
    args = parser.parse_args()
    
    success = asyncio.run(text_to_speech(args.text, args.output, args.voice, args.rate))
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()