(() => {
  const demo = document.querySelector('.phone-demo');
  if (!demo) return;

  const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
  if (motion.matches || !('IntersectionObserver' in window)) return;

  const copy = demo.querySelector('.phone-reply-copy');
  const stream = demo.querySelector('.phone-stream');
  const words = copy.firstElementChild.textContent.trim().split(/\s+/);
  let timer;

  function finish() {
    window.clearTimeout(timer);
    copy.classList.remove('is-streaming');
    stream.hidden = true;
    stream.textContent = '';
  }

  const observer = new IntersectionObserver(entries => {
    if (motion.matches || !entries.some(entry => entry.isIntersecting)) return;
    observer.disconnect();

    stream.hidden = false;
    copy.classList.add('is-streaming');
    let count = 0;
    function next() {
      stream.textContent = words.slice(0, ++count).join(' ');
      timer = window.setTimeout(count === words.length ? finish : next, 85);
    }
    timer = window.setTimeout(next, 380);
  }, { threshold: 0.55 });

  observer.observe(demo.querySelector('.phone-frame'));
  motion.addEventListener('change', () => {
    if (motion.matches) {
      observer.disconnect();
      finish();
    }
  });
})();
