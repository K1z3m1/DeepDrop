/**
 * lw Browser - Interactive Features & UI Controller
 * High-performance, lightweight vanilla JavaScript.
 */

document.addEventListener('DOMContentLoaded', () => {
  initHeroPhone();
  initMobileMenu();
  initFaqAccordion();
  initLightbox();
  initHeaderScroll();
});

/**
 * Hero Phone Interactive Screenshot Switcher
 */
function initHeroPhone() {
  const buttons = document.querySelectorAll('.hero-pill-btn');
  const heroImg = document.getElementById('heroPhoneImage');
  
  if (!buttons.length || !heroImg) return;

  buttons.forEach(btn => {
    btn.addEventListener('click', () => {
      const targetSrc = btn.getAttribute('data-img');
      const targetAlt = btn.getAttribute('data-alt') || 'lw Browser';

      if (!targetSrc || btn.classList.contains('active')) return;

      // Update active states
      buttons.forEach(b => b.classList.remove('active'));
      btn.classList.add('active');

      // Smooth fade transition
      heroImg.style.opacity = '0';
      heroImg.style.transform = 'scale(0.97)';

      setTimeout(() => {
        heroImg.src = targetSrc;
        heroImg.alt = targetAlt;
        heroImg.style.opacity = '1';
        heroImg.style.transform = 'scale(1)';
      }, 180);
    });
  });
}

/**
 * Mobile Navigation Drawer Toggle
 */
function initMobileMenu() {
  const toggleBtn = document.getElementById('mobileToggleBtn');
  const navMenu = document.getElementById('navMenu');
  const navLinks = document.querySelectorAll('.nav-item-link');

  if (!toggleBtn || !navMenu) return;

  const toggle = () => {
    const isOpen = navMenu.classList.toggle('mobile-open');
    toggleBtn.setAttribute('aria-expanded', isOpen);
    
    // Switch icon between hamburger and close
    const icon = toggleBtn.querySelector('svg');
    if (icon) {
      if (isOpen) {
        icon.innerHTML = '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"/>';
      } else {
        icon.innerHTML = '<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16"/>';
      }
    }
  };

  toggleBtn.addEventListener('click', toggle);

  // Close menu when clicking any nav link
  navLinks.forEach(link => {
    link.addEventListener('click', () => {
      if (navMenu.classList.contains('mobile-open')) {
        toggle();
      }
    });
  });
}

/**
 * FAQ Accordion Interaction
 */
function initFaqAccordion() {
  const faqItems = document.querySelectorAll('.faq-item');

  faqItems.forEach(item => {
    const header = item.querySelector('.faq-header');
    if (!header) return;

    header.addEventListener('click', () => {
      const isActive = item.classList.contains('active');

      // Close all other items for a clean accordion effect
      faqItems.forEach(otherItem => {
        if (otherItem !== item) {
          otherItem.classList.remove('active');
        }
      });

      // Toggle current item
      item.classList.toggle('active', !isActive);
    });
  });
}

/**
 * Lightbox Modal for Screenshots
 */
function initLightbox() {
  const modal = document.getElementById('lightboxModal');
  const modalImg = document.getElementById('lightboxImg');
  const closeBtn = document.getElementById('lightboxCloseBtn');
  const triggerElements = document.querySelectorAll('[data-lightbox-trigger]');

  if (!modal || !modalImg) return;

  const openLightbox = (src, alt) => {
    modalImg.src = src;
    modalImg.alt = alt || 'lw Browser Captura';
    modal.classList.add('open');
    document.body.style.overflow = 'hidden';
  };

  const closeLightbox = () => {
    modal.classList.remove('open');
    document.body.style.overflow = '';
  };

  triggerElements.forEach(trigger => {
    trigger.addEventListener('click', (e) => {
      e.preventDefault();
      const img = trigger.querySelector('img') || trigger;
      const src = trigger.getAttribute('data-lightbox-src') || img.src;
      const alt = img.alt || 'lw Browser Screenshot';
      openLightbox(src, alt);
    });
  });

  if (closeBtn) {
    closeBtn.addEventListener('click', closeLightbox);
  }

  modal.addEventListener('click', (e) => {
    if (e.target === modal) {
      closeLightbox();
    }
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && modal.classList.contains('open')) {
      closeLightbox();
    }
  });
}

/**
 * Subtle Header Shadow on Scroll
 */
function initHeaderScroll() {
  const header = document.querySelector('.site-header');
  if (!header) return;

  const onScroll = () => {
    if (window.scrollY > 20) {
      header.style.boxShadow = '0 10px 30px -10px rgba(0, 0, 0, 0.7)';
      header.style.borderColor = 'rgba(255, 255, 255, 0.12)';
    } else {
      header.style.boxShadow = 'none';
      header.style.borderColor = 'var(--border-subtle)';
    }
  };

  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
}
