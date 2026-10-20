import { Routes, Route, Navigate } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import RequireAuth from './components/RequireAuth';
import LoginPage from './pages/LoginPage';
import AgentList from './pages/agents/AgentList';
import ChatPage from './pages/chat/ChatPage';
import ExtensionsPage from './pages/extensions/ExtensionsPage';
import OpsLogsPage from './pages/ops/OpsLogsPage';
import DiagnosisPage from './pages/ops/DiagnosisPage';
import PromptOptimizePage from './pages/ops/PromptOptimizePage';
import ToolsPage from './pages/ops/ToolsPage';
import QuotaPage from './pages/ops/QuotaPage';
import KnowledgeBasePage from './pages/rag/KnowledgeBasePage';
import WorkflowsPage from './pages/workflows/WorkflowsPage';
import FilesPage from './pages/files/FilesPage';
import PlatformSettingsPage from './pages/settings/PlatformSettingsPage';
import UserManagePage from './pages/system/UserManagePage';
import RoleManagePage from './pages/system/RoleManagePage';
import DictManagePage from './pages/system/DictManagePage';
import NotificationsPage from './pages/notifications/NotificationsPage';
import MemoryPage from './pages/memory/MemoryPage';
import ApprovalPage from './pages/ops/ApprovalPage';

export default function App() {
  return (
    <Routes>
      {/* 登录页在 AppLayout 之外：未登录时不该出现任何导航入口 */}
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        {/*
          默认落到对话页（2026-10-16）：平台的主用途就是"和智能体说话"，
          概览页那类静态导航看板没有行动价值 —— 像 DSH 一样开门见山。
          /overview 保留为重定向：老书签 / 皮肤 / 文档里的旧链接不 404。
        */}
        <Route path="/" element={<Navigate to="/chat" replace />} />
        <Route path="/overview" element={<Navigate to="/chat" replace />} />
        <Route path="/agents" element={<AgentList />} />
        <Route path="/chat" element={<ChatPage />} />
        {/* 会话历史已并入对话页（2026-10-16，页内抽屉：切换/重命名/清空/删除）；旧路径重定向，老书签不失效 */}
        <Route path="/sessions" element={<Navigate to="/chat" replace />} />
        <Route path="/knowledge-bases" element={<KnowledgeBasePage />} />
        <Route path="/workflows" element={<WorkflowsPage />} />
        {/*
          「扩展」：插件 / Skills / 皮肤市场三合一（2026-10-16，与「日志与观测」同一模式）。
          三个旧路径**重定向**到对应 Tab 而不是删除 —— 站内跳转（如工具页的"去插件页"）
          与老书签都在用它们。
        */}
        <Route path="/extensions" element={<ExtensionsPage />} />
        {/* 2026-10-19：插件 / 界面插件已合并进「插件市场」；皮肤保留独立 Tab（外部 catalog 太慢） */}
        <Route path="/plugins" element={<Navigate to="/extensions?tab=market" replace />} />
        <Route path="/skins" element={<Navigate to="/extensions?tab=skins" replace />} />
        <Route path="/skills" element={<Navigate to="/extensions?tab=skills" replace />} />
        <Route path="/files" element={<FilesPage />} />
        {/*
          「日志与观测」：运行日志 / 智能体可观测性 / 操作日志 / 统计报表
          四个同源页面合并成一个入口（读的是同一批表，见 OpsLogsPage 的类注释）。
          三个旧路径**重定向**过去而不是删除 —— 老书签与文档链接仍在使用它们。
        */}
        <Route path="/logs" element={<OpsLogsPage />} />
        <Route path="/observability" element={<Navigate to="/logs?tab=observability" replace />} />
        <Route path="/audit" element={<Navigate to="/logs?tab=audit" replace />} />
        <Route path="/reports" element={<Navigate to="/logs?tab=reports" replace />} />
        <Route path="/diagnosis" element={<DiagnosisPage />} />
        <Route path="/prompt" element={<PromptOptimizePage />} />
        <Route path="/tools" element={<ToolsPage />} />
        <Route path="/quota" element={<QuotaPage />} />
        <Route path="/settings" element={<PlatformSettingsPage />} />
        <Route path="/system/users" element={<UserManagePage />} />
        <Route path="/system/roles" element={<RoleManagePage />} />
        <Route path="/system/dicts" element={<DictManagePage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/memory" element={<MemoryPage />} />
        <Route path="/approvals" element={<ApprovalPage />} />
      </Route>
    </Routes>
  );
}
