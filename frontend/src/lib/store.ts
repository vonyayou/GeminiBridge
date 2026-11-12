import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import type { ResponseObject } from './types'

export interface Message { id: string; role: 'user' | 'assistant'; content: string; reasoning?: string; thinking?: boolean; model?: string; error?: string; durationMs?: number; usage?: ResponseObject['usage']; stopped?: boolean }
export interface Chat { id: string; title: string; model: string; instructions: string; thinking?: boolean; messages: Message[]; updatedAt: number }
const chatStorage = createJSONStorage(() => ({
  getItem: (name: string) => { try { return localStorage.getItem(name) } catch { return null } },
  setItem: (name: string, value: string) => { try { localStorage.setItem(name, value) } catch { useUi.getState().notify('Browser storage is full or unavailable. Delete older chats to save this conversation.') } },
  removeItem: (name: string) => { try { localStorage.removeItem(name) } catch { /* storage unavailable */ } },
}))

interface ChatsState {
  chats: Chat[]; activeId: string | null
  createChat: (model?: string) => string
  selectChat: (id: string) => void
  updateChat: (id: string, update: Partial<Chat>) => void
  deleteChat: (id: string) => void
  addMessage: (chatId: string, message: Message) => void
  updateMessage: (chatId: string, messageId: string, update: Partial<Message>) => void
}
export const useChats = create<ChatsState>()(persist((set) => ({
  chats: [], activeId: null,
  createChat: (model = '') => {
    const id = crypto.randomUUID()
    set(state => ({ activeId: id, chats: [{ id, title: 'New conversation', model, instructions: '', messages: [], updatedAt: Date.now() }, ...state.chats].slice(0, 20) }))
    return id
  },
  selectChat: (activeId) => set({ activeId }),
  updateChat: (id, update) => set(state => ({ chats: state.chats.map(chat => chat.id === id ? { ...chat, ...update, updatedAt: Date.now() } : chat) })),
  deleteChat: (id) => set(state => ({ chats: state.chats.filter(chat => chat.id !== id), activeId: state.activeId === id ? null : state.activeId })),
  addMessage: (chatId, message) => set(state => ({ chats: state.chats.map(chat => chat.id === chatId ? { ...chat, messages: [...chat.messages, message].slice(-200), title: message.role === 'user' && chat.messages.length === 0 ? message.content.slice(0, 44) : chat.title, updatedAt: Date.now() } : chat) })),
  updateMessage: (chatId, messageId, update) => set(state => ({ chats: state.chats.map(chat => chat.id === chatId ? { ...chat, messages: chat.messages.map(message => message.id === messageId ? { ...message, ...update } : message) } : chat) })),
}), { name: 'geminibridge.playground.v1', storage: chatStorage, partialize: state => ({ chats: state.chats, activeId: state.activeId }) }))

interface UiState { collapsed: boolean; mobileOpen: boolean; toast: string | null; setCollapsed: (value: boolean) => void; setMobileOpen: (value: boolean) => void; notify: (message: string) => void; dismiss: () => void }
export const useUi = create<UiState>((set) => ({ collapsed: false, mobileOpen: false, toast: null, setCollapsed: collapsed => set({ collapsed }), setMobileOpen: mobileOpen => set({ mobileOpen }), notify: toast => set({ toast }), dismiss: () => set({ toast: null }) }))
