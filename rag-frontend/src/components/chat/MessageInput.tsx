import React, { useRef, useState, KeyboardEvent } from 'react'
import { Send, Paperclip, Image, X } from 'lucide-react'

interface MessageInputProps {
  onSend: (text: string, files: File[], images: string[]) => Promise<void>
  disabled: boolean
  history?: string[]   // 과거 user 메시지 (오래된 순)
}

export default function MessageInput({ onSend, disabled, history = [] }: MessageInputProps) {
  const [text, setText] = useState('')
  const [attachedFiles, setAttachedFiles] = useState<File[]>([])
  const [attachedImages, setAttachedImages] = useState<{ name: string; base64: string }[]>([])
  const [historyIndex, setHistoryIndex] = useState(-1)  // -1 = 히스토리 탐색 중 아님
  const draftRef = useRef('')                            // 탐색 시작 전 입력 중이던 텍스트

  const fileInputRef = useRef<HTMLInputElement>(null)
  const imageInputRef = useRef<HTMLInputElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const canSend = (text.trim().length > 0 || attachedFiles.length > 0 || attachedImages.length > 0) && !disabled

  const handleSend = async () => {
    if (!canSend) return
    const base64List = attachedImages.map(img => img.base64)
    await onSend(text.trim(), attachedFiles, base64List)
    setText('')
    setAttachedFiles([])
    setAttachedImages([])
    setHistoryIndex(-1)
    draftRef.current = ''
    textareaRef.current?.focus()
  }

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
      return
    }

    if (e.key === 'ArrowUp') {
      const el = textareaRef.current
      // 커서가 맨 앞이거나 이미 히스토리 탐색 중일 때만 인터셉트
      if (el && (el.selectionStart === 0 || historyIndex !== -1) && history.length > 0) {
        e.preventDefault()
        if (historyIndex === -1) {
          // 탐색 시작 — 현재 입력 중인 텍스트 저장
          draftRef.current = text
          const idx = history.length - 1
          setHistoryIndex(idx)
          setText(history[idx])
        } else if (historyIndex > 0) {
          const idx = historyIndex - 1
          setHistoryIndex(idx)
          setText(history[idx])
        }
      }
      return
    }

    if (e.key === 'ArrowDown') {
      if (historyIndex !== -1) {
        e.preventDefault()
        if (historyIndex < history.length - 1) {
          const idx = historyIndex + 1
          setHistoryIndex(idx)
          setText(history[idx])
        } else {
          // 현재(드래프트)로 복귀
          setHistoryIndex(-1)
          setText(draftRef.current)
        }
      }
    }
  }

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? [])
    setAttachedFiles(prev => [...prev, ...files])
    e.target.value = ''
  }

  const handleImageChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? [])
    files.forEach(file => {
      const reader = new FileReader()
      reader.onload = () => {
        setAttachedImages(prev => [
          ...prev,
          { name: file.name, base64: reader.result as string },
        ])
      }
      reader.readAsDataURL(file)
    })
    e.target.value = ''
  }

  const removeFile = (index: number) => {
    setAttachedFiles(prev => prev.filter((_, i) => i !== index))
  }

  const removeImage = (index: number) => {
    setAttachedImages(prev => prev.filter((_, i) => i !== index))
  }

  return (
    <div className="border-t border-gray-200 bg-white px-4 py-3">
      {/* 첨부 파일 미리보기 */}
      {(attachedFiles.length > 0 || attachedImages.length > 0) && (
        <div className="flex flex-wrap gap-2 mb-2">
          {attachedFiles.map((file, i) => (
            <div
              key={`file-${i}`}
              className="flex items-center gap-1 bg-gray-100 rounded-full px-3 py-1 text-xs text-gray-700"
            >
              <Paperclip size={12} />
              <span className="max-w-[120px] truncate">{file.name}</span>
              <button onClick={() => removeFile(i)} className="ml-1 text-gray-400 hover:text-gray-700">
                <X size={12} />
              </button>
            </div>
          ))}
          {attachedImages.map((img, i) => (
            <div
              key={`img-${i}`}
              className="flex items-center gap-1 bg-blue-50 rounded-full px-3 py-1 text-xs text-blue-700"
            >
              <Image size={12} />
              <span className="max-w-[120px] truncate">{img.name}</span>
              <button onClick={() => removeImage(i)} className="ml-1 text-blue-400 hover:text-blue-700">
                <X size={12} />
              </button>
            </div>
          ))}
        </div>
      )}

      <div className="flex items-end gap-2">
        {/* 파일 첨부 */}
        <input ref={fileInputRef} type="file" accept="*/*" multiple className="hidden" onChange={handleFileChange} />
        <button
          onClick={() => fileInputRef.current?.click()}
          disabled={disabled}
          className="p-2 text-gray-400 hover:text-gray-600 disabled:opacity-40 transition-colors"
          title="파일 첨부"
        >
          <Paperclip size={20} />
        </button>

        {/* 이미지 첨부 */}
        <input ref={imageInputRef} type="file" accept="image/*" multiple className="hidden" onChange={handleImageChange} />
        <button
          onClick={() => imageInputRef.current?.click()}
          disabled={disabled}
          className="p-2 text-gray-400 hover:text-gray-600 disabled:opacity-40 transition-colors"
          title="이미지 첨부"
        >
          <Image size={20} />
        </button>

        {/* 텍스트 입력 */}
        <textarea
          ref={textareaRef}
          value={text}
          onChange={e => setText(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          rows={1}
          placeholder="메시지를 입력하세요 (Enter 전송, Shift+Enter 줄바꿈)"
          className="flex-1 border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none max-h-40 overflow-y-auto disabled:opacity-40"
          style={{ lineHeight: '1.5' }}
        />

        {/* 전송 버튼 */}
        <button
          onClick={handleSend}
          disabled={!canSend}
          className="p-2 bg-blue-600 hover:bg-blue-700 disabled:bg-gray-200 text-white disabled:text-gray-400 rounded-lg transition-colors"
          title="전송"
        >
          <Send size={20} />
        </button>
      </div>
    </div>
  )
}
