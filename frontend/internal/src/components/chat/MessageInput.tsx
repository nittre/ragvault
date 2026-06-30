import React, { useRef, useState, KeyboardEvent } from 'react'
import { Send, Paperclip, Image, X } from 'lucide-react'
import heic2any from 'heic2any'

interface MessageInputProps {
  onSend: (text: string, files: File[], images: string[]) => Promise<void>
  disabled: boolean
  history?: string[]
}

const SLASH_COMMANDS = [
  { cmd: 'web', icon: '🌐', label: '/web', desc: '웹 검색 우선 (폴백: 내부 문서)' },
  { cmd: 'rag', icon: '📄', label: '/rag', desc: '내부 문서 우선 (폴백: 웹 검색)' },
  { cmd: 'sql', icon: '📊', label: '/sql', desc: 'SQL 직접 실행 (분류기 우회)' },
]

export default function MessageInput({ onSend, disabled, history = [] }: MessageInputProps) {
  const [text, setText] = useState('')
  const [attachedFiles, setAttachedFiles] = useState<File[]>([])
  const [attachedImages, setAttachedImages] = useState<{ name: string; base64: string }[]>([])
  const [historyIndex, setHistoryIndex] = useState(-1)
  const [slashIndex, setSlashIndex] = useState(0)
  const draftRef = useRef('')

  const fileInputRef = useRef<HTMLInputElement>(null)
  const imageInputRef = useRef<HTMLInputElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // 슬래시 메뉴: 텍스트가 "/" 또는 "/문자" (공백 없음) 일 때 표시
  const slashMatch = text.match(/^\/([a-z]*)$/)
  const showSlashMenu = slashMatch !== null && !disabled
  const slashFilter = slashMatch?.[1] ?? ''
  const filteredCommands = SLASH_COMMANDS.filter(c => c.cmd.startsWith(slashFilter))

  const selectSlashCommand = (cmd: string) => {
    setText(`/${cmd} `)
    setSlashIndex(0)
    setTimeout(() => {
      const el = textareaRef.current
      if (el) { el.focus(); el.setSelectionRange(el.value.length, el.value.length) }
    }, 0)
  }

  const canSend = (text.trim().length > 0 || attachedFiles.length > 0 || attachedImages.length > 0) && !disabled

  const handleSend = async () => {
    if (!canSend) return
    const base64List = attachedImages.map(img => img.base64)
    await onSend(text.trim(), attachedFiles, base64List)
    setText('')
    setAttachedFiles([])
    setAttachedImages([])
    setHistoryIndex(-1)
    setSlashIndex(0)
    draftRef.current = ''
    textareaRef.current?.focus()
  }

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    // 슬래시 메뉴가 열려 있을 때
    if (showSlashMenu && filteredCommands.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault()
        setSlashIndex(i => (i + 1) % filteredCommands.length)
        return
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault()
        setSlashIndex(i => (i - 1 + filteredCommands.length) % filteredCommands.length)
        return
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault()
        selectSlashCommand(filteredCommands[slashIndex]?.cmd ?? filteredCommands[0].cmd)
        return
      }
      if (e.key === 'Escape') {
        e.preventDefault()
        setText('')
        return
      }
    }

    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
      return
    }

    if (e.key === 'ArrowUp') {
      const el = textareaRef.current
      if (el && (el.selectionStart === 0 || historyIndex !== -1) && history.length > 0) {
        e.preventDefault()
        if (historyIndex === -1) {
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
          setHistoryIndex(-1)
          setText(draftRef.current)
        }
      }
    }
  }

  const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setText(e.target.value)
    setSlashIndex(0)
  }

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? [])
    setAttachedFiles(prev => [...prev, ...files])
    e.target.value = ''
  }

  const handleImageChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? [])
    e.target.value = ''
    for (const file of files) {
      const isHeic = file.type === 'image/heic' || file.type === 'image/heif' ||
                     file.name.toLowerCase().endsWith('.heic') || file.name.toLowerCase().endsWith('.heif')
      let blob: Blob = file
      let name = file.name
      if (isHeic) {
        try {
          const converted = await heic2any({ blob: file, toType: 'image/jpeg', quality: 0.85 })
          blob = Array.isArray(converted) ? converted[0] : converted
          name = name.replace(/\.(heic|heif)$/i, '.jpg')
        } catch {
          console.error('HEIC 변환 실패', file.name)
          continue
        }
      }
      const reader = new FileReader()
      reader.onload = () => {
        setAttachedImages(prev => [...prev, { name, base64: reader.result as string }])
      }
      reader.readAsDataURL(blob)
    }
  }

  const removeFile = (index: number) => setAttachedFiles(prev => prev.filter((_, i) => i !== index))
  const removeImage = (index: number) => setAttachedImages(prev => prev.filter((_, i) => i !== index))

  return (
    <div className="border-t border-gray-200 bg-white px-4 py-3 relative">
      {/* 슬래시 커맨드 피커 */}
      {showSlashMenu && filteredCommands.length > 0 && (
        <div className="absolute bottom-full left-4 right-4 mb-1 bg-white border border-gray-200 rounded-xl shadow-lg overflow-hidden z-10">
          <div className="px-3 py-1.5 text-xs text-gray-400 border-b border-gray-100">라우팅 커맨드</div>
          {filteredCommands.map((c, i) => (
            <button
              key={c.cmd}
              onMouseDown={e => { e.preventDefault(); selectSlashCommand(c.cmd) }}
              className={`w-full flex items-center gap-3 px-3 py-2.5 text-left transition-colors ${
                i === slashIndex ? 'bg-blue-50' : 'hover:bg-gray-50'
              }`}
            >
              <span className="text-base">{c.icon}</span>
              <span className="font-medium text-sm text-gray-800">{c.label}</span>
              <span className="text-xs text-gray-400">{c.desc}</span>
            </button>
          ))}
        </div>
      )}

      {/* 첨부 파일 미리보기 */}
      {(attachedFiles.length > 0 || attachedImages.length > 0) && (
        <div className="flex flex-wrap gap-2 mb-2">
          {attachedFiles.map((file, i) => (
            <div key={`file-${i}`} className="flex items-center gap-1 bg-gray-100 rounded-full px-3 py-1 text-xs text-gray-700">
              <Paperclip size={12} />
              <span className="max-w-[120px] truncate">{file.name}</span>
              <button onClick={() => removeFile(i)} className="ml-1 text-gray-400 hover:text-gray-700"><X size={12} /></button>
            </div>
          ))}
          {attachedImages.map((img, i) => (
            <div key={`img-${i}`} className="flex items-center gap-1 bg-blue-50 rounded-full px-3 py-1 text-xs text-blue-700">
              <Image size={12} />
              <span className="max-w-[120px] truncate">{img.name}</span>
              <button onClick={() => removeImage(i)} className="ml-1 text-blue-400 hover:text-blue-700"><X size={12} /></button>
            </div>
          ))}
        </div>
      )}

      <div className="flex items-end gap-2">
        <input ref={fileInputRef} type="file" accept=".pdf,.docx,.doc,.pptx,.ppt,.xlsx,.xls,.txt,.md,.csv" multiple className="hidden" onChange={handleFileChange} />
        <button
          onClick={() => fileInputRef.current?.click()}
          disabled={disabled}
          className="p-2 text-gray-400 hover:text-gray-600 disabled:opacity-40 transition-colors"
          title="파일 첨부"
        >
          <Paperclip size={20} />
        </button>

        <input ref={imageInputRef} type="file" accept="image/*" multiple className="hidden" onChange={handleImageChange} />
        <button
          onClick={() => imageInputRef.current?.click()}
          disabled={disabled}
          className="p-2 text-gray-400 hover:text-gray-600 disabled:opacity-40 transition-colors"
          title="이미지 첨부"
        >
          <Image size={20} />
        </button>

        <textarea
          ref={textareaRef}
          value={text}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          rows={1}
          placeholder="메시지를 입력하세요 (Enter 전송, Shift+Enter 줄바꿈, / 커맨드)"
          className="flex-1 border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none max-h-40 overflow-y-auto disabled:opacity-40"
          style={{ lineHeight: '1.5' }}
        />

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
