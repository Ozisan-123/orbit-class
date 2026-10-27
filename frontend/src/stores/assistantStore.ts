import { create } from 'zustand';
import type { AssistantAction, AssistantConversation, AssistantMessage, AssistantUsage } from '../types';

interface AssistantState {
  open: boolean;
  conversations: AssistantConversation[];
  conversationId?: number;
  messages: AssistantMessage[];
  actions: AssistantAction[];
  usage?: AssistantUsage;
  streaming: boolean;
  streamText: string;
  liveTool?: { tool: string; arguments: Record<string, unknown> };
  error?: string;
  set: (patch: Partial<AssistantState>) => void;
  appendStream: (text: string) => void;
  resetConversation: () => void;
}

export const useAssistantStore = create<AssistantState>(set => ({
  open: false,
  conversations: [],
  messages: [],
  actions: [],
  streaming: false,
  streamText: '',
  set,
  appendStream: text => set(state => ({ streamText: state.streamText + text })),
  resetConversation: () => set({ conversationId: undefined, messages: [], actions: [], streamText: '', error: undefined }),
}));
