import { create } from 'zustand';
import { getTenantId, setTenantId, getToken, setToken } from '../api/http';

interface AppState {
  tenantId: string;
  token: string | null;
  setTenant: (t: string) => void;
  setAuth: (t: string | null) => void;
}

export const useAppStore = create<AppState>((set) => ({
  tenantId: getTenantId(),
  token: getToken(),
  setTenant: (t) => {
    setTenantId(t);
    set({ tenantId: t });
  },
  setAuth: (t) => {
    setToken(t);
    set({ token: t });
  },
}));