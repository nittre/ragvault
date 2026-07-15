import { useState, type ReactNode } from 'react'

interface TooltipProps {
  text: string
  children: ReactNode
  side?: 'top' | 'bottom'
  /** 'center'(기본): 트리거 중앙 기준. 'end': 트리거 오른쪽 끝에 맞춰 왼쪽으로 펼침 (화면 오른쪽 끝 버튼용). */
  align?: 'center' | 'end'
  className?: string
}

/**
 * 네이티브 title 속성 대체용 커스텀 호버 툴팁.
 * 지연 없이 즉시 나타나는 스타일된 말풍선.
 */
export default function Tooltip({ text, children, side = 'top', align = 'center', className = '' }: TooltipProps) {
  const [show, setShow] = useState(false)

  const verticalClass = side === 'top' ? 'bottom-full mb-2' : 'top-full mt-2'
  const horizontalClass = align === 'end' ? 'right-0' : 'left-1/2 -translate-x-1/2'
  const positionClass = `${verticalClass} ${horizontalClass}`

  const arrowVerticalClass = side === 'top' ? 'top-full border-t-gray-900' : 'bottom-full border-b-gray-900'
  const arrowHorizontalClass = align === 'end' ? 'right-3' : 'left-1/2 -translate-x-1/2'
  const arrowClass = `${arrowVerticalClass} ${arrowHorizontalClass}`

  return (
    <span
      className={`relative inline-flex ${className}`}
      onMouseEnter={() => setShow(true)}
      onMouseLeave={() => setShow(false)}
    >
      {children}
      {show && (
        <span
          role="tooltip"
          className={`pointer-events-none absolute z-50 ${positionClass} whitespace-nowrap rounded-lg bg-gray-900 px-3 py-1.5 text-xs font-medium text-white shadow-lg`}
        >
          {text}
          <span className={`absolute h-0 w-0 border-4 border-transparent ${arrowClass}`} />
        </span>
      )}
    </span>
  )
}
