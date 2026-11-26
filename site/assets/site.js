(() => {
  const buttons = [...document.querySelectorAll('[data-screen]')];
  const screens = [...document.querySelectorAll('.demo-screen')];
  const select = (id) => {
    if (!screens.some(screen => screen.id === id)) return;
    for (const screen of screens) screen.hidden = screen.id !== id;
    for (const button of buttons) button.setAttribute('aria-pressed', String(button.dataset.screen === id));
  };
  for (const button of buttons) button.addEventListener('click', () => select(button.dataset.screen));
  select('overview');
  const toggle = document.getElementById('theme-toggle');
  const phone = document.getElementById('demo-phone');
  toggle.addEventListener('click', () => {
    const dark = phone.classList.toggle('dark');
    toggle.setAttribute('aria-pressed', String(dark));
  });
})();
