#!/usr/bin/env python3
"""
Open-source Whisper transcription script
Called by Java via ProcessBuilder
"""

import sys
import json
import argparse
import warnings

warnings.filterwarnings("ignore")

def transcribe(audio_path, model_size="base", language=None, output_json=None, device="cuda"):
    """
    Transcribe audio using local Whisper (open source)
    """
    try:
        # Try faster-whisper first (much faster)
        try:
            # Load the installed PyTorch CUDA libraries for CTranslate2 on Windows.
            if sys.platform == "win32" and device == "cuda":
                import os
                from pathlib import Path
                import torch

                cuda_lib = Path(torch.__file__).parent / "lib"
                cuda_handle = os.add_dll_directory(str(cuda_lib))

            from faster_whisper import WhisperModel
            
            print(f"Using faster-whisper with model: {model_size}", file=sys.stderr)
            
            # Determine compute type based on device
            compute_type = "float16" if device == "cuda" else "int8"
            
            model = WhisperModel(model_size, device=device, compute_type=compute_type)
            
            segments, info = model.transcribe(
                audio_path,
                language=language,
                word_timestamps=True,
                condition_on_previous_text=True
            )
            
            result = {
                "text": "",
                "language": info.language,
                "duration": info.duration,
                "segments": []
            }
            
            for segment in segments:
                seg_data = {
                    "id": len(result["segments"]),
                    "start": segment.start,
                    "end": segment.end,
                    "text": segment.text.strip(),
                    "words": []
                }
                
                if segment.words:
                    for word in segment.words:
                        seg_data["words"].append({
                            "word": word.word.strip(),
                            "start": word.start,
                            "end": word.end,
                            "probability": word.probability
                        })
                
                result["segments"].append(seg_data)
                result["text"] += segment.text + " "
            
            result["text"] = result["text"].strip()
            
        except ImportError:
            # Fallback to original openai-whisper
            import whisper
            
            print(f"Using openai-whisper with model: {model_size}", file=sys.stderr)
            
            model = whisper.load_model(model_size).to(device)
            
            options = {
                "language": language,
                "task": "transcribe",
                "word_timestamps": True
            }
            
            result = model.transcribe(audio_path, **options)
            
            # Normalize output
            normalized = {
                "text": result["text"],
                "language": result.get("language", "unknown"),
                "duration": result.get("duration", 0),
                "segments": []
            }
            
            for seg in result.get("segments", []):
                normalized["segments"].append({
                    "id": seg.get("id", 0),
                    "start": seg.get("start", 0),
                    "end": seg.get("end", 0),
                    "text": seg.get("text", "").strip(),
                    "words": seg.get("words", [])
                })
            
            result = normalized
        
        # Save or print JSON
        if output_json:
            with open(output_json, 'w', encoding='utf-8') as f:
                json.dump(result, f, ensure_ascii=False, indent=2)
            print(f"Saved transcript to: {output_json}")
        else:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        
        return result
        
    except Exception as e:
        print(f"ERROR: {str(e)}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Transcribe audio using local Whisper")
    parser.add_argument("audio", help="Path to audio file")
    parser.add_argument("--model", default="base", help="Model size")
    parser.add_argument("--language", default=None, help="Language code")
    parser.add_argument("--output", default=None, help="Output JSON file")
    parser.add_argument("--device", default="cuda", help="Device: cuda or cpu")
    
    args = parser.parse_args()
    transcribe(args.audio, args.model, args.language, args.output, args.device)
