import { createApp } from "vue";
import "./styles.css";
import App from "./App.vue";
import TrayMenu from "./components/TrayMenu.vue";
import { initTheme } from "./api";

initTheme();
createApp(new URLSearchParams(window.location.search).has("tray") ? TrayMenu : App).mount("#app");
